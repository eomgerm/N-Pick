"""잡 API 클라이언트. 워커가 발신자다.

방향은 아키텍처 정본이 정했다 — 서비스 서버가 잡 API 를 노출하고 워커가 long-poll 한다
(`docs/architecture/02-container.md`). 워커에는 잡을 받는 인바운드 포트가 없고
아웃바운드 443 만 쓴다. GPU 파드가 죽어도 lease 만료로 회수되므로 잡이 유실되지 않는
성질이 여기서 나온다.

배포판·임포트 이름이 모두 `httpx2` 다. `httpx` 는 이 환경에 설치되어 있지 않다.
"""

import asyncio
import logging
import random
from collections.abc import Mapping
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any, Final
from urllib.parse import quote

import httpx2

from npick_worker.jobs.errors import (
    ArtifactHashMismatchError,
    ArtifactKeyRejectedError,
    ArtifactUploadError,
    InputDownloadError,
    InputUnavailableError,
    JobApiConflictError,
    JobApiInvalidRequestError,
    JobApiUnauthorizedError,
    JobApiUnavailableError,
    LeaseLostError,
    StageAlreadyCompletedError,
    WorkerError,
)
from npick_worker.jobs.models import (
    ClaimRequest,
    ClaimResponse,
    CompleteAck,
    HeartbeatAck,
    HeartbeatRequest,
    StageResult,
)

logger = logging.getLogger(__name__)

#: 계약이 정한 베이스 경로. `internal` 세그먼트로 BE 가 필터 체인을 분리한다.
JOB_API_PREFIX: Final[str] = "/api/v1/internal/jobs"

#: claim 의 read timeout 은 서버 대기 시간에 이만큼을 더한다. 서버가 정상적으로
#: 빈 응답을 돌려주는 것(대기 만료)과 클라이언트가 못 기다린 것(비정상)을 갈라야 한다.
_POLL_TIMEOUT_MARGIN_SECONDS: Final[float] = 10.0

_RETRYABLE_STATUS: Final[frozenset[int]] = frozenset({408, 429, 500, 502, 503, 504})
_BACKOFF_BASE_SECONDS: Final[float] = 1.0

#: 다운로드 청크. 원본 영상이 기가바이트급이라 통째로 메모리에 올리지 않는다.
_DOWNLOAD_CHUNK_BYTES: Final[int] = 1 << 20


class JobApiClient:
    """네 엔드포인트만 안다. 상태는 갖지 않는다 — 루프는 `JobRunner` 의 몫이다."""

    def __init__(
        self,
        *,
        base_url: str,
        token: str,
        worker_id: str,
        connect_timeout: float,
        read_timeout: float,
        poll_wait_seconds: int,
        max_backoff_seconds: float,
        max_attempts: int = 5,
        transport: httpx2.AsyncBaseTransport | None = None,
    ) -> None:
        self._poll_wait_seconds = poll_wait_seconds
        self._connect_timeout = connect_timeout
        self._read_timeout = read_timeout
        self._max_backoff_seconds = max_backoff_seconds
        self._max_attempts = max_attempts
        self._worker_id = worker_id
        self._artifact_leases: dict[str, str] = {}
        self._default_timeout = httpx2.Timeout(
            connect=connect_timeout,
            read=read_timeout,
            write=read_timeout,
            pool=connect_timeout,
        )
        headers = {"X-Worker-Id": worker_id, "Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        self._client = httpx2.AsyncClient(
            base_url=base_url.rstrip("/"),
            transport=transport,
            headers=headers,
            timeout=self._default_timeout,
            # 리다이렉트를 따라가면 Authorization 헤더가 다른 호스트로 새어 나간다.
            follow_redirects=False,
        )

    @property
    def worker_id(self) -> str:
        """이 클라이언트가 헤더에 싣는 ID. 잡 본문의 값과 같아야 한다."""
        return self._worker_id

    async def aclose(self) -> None:
        await self._client.aclose()

    def bind_artifact_lease(self, run_id: str, lease_id: str) -> None:
        self._artifact_leases[run_id] = lease_id

    def release_artifact_lease(self, run_id: str) -> None:
        self._artifact_leases.pop(run_id, None)

    def _artifact_headers(self, run_id: str) -> dict[str, str]:
        lease = self._artifact_leases.get(run_id)
        return {"X-Job-Lease-Id": lease} if lease is not None else {}

    # ── 엔드포인트 ───────────────────────────────────────────────────

    async def claim(self, request: ClaimRequest) -> ClaimResponse:
        """잡을 하나 받아온다. 배정이 없으면 `assigned=False` 로 돌아온다.

        대기는 서버가 한다. 빈 응답은 실패가 아니므로 호출자는 **쉬지 않고** 다시
        부른다 — 여기서 또 자면 유휴 주기가 두 배가 된다.
        """
        response = await self._send(
            "POST",
            f"{JOB_API_PREFIX}/claim",
            json=_dump(request),
            timeout=httpx2.Timeout(
                connect=self._connect_timeout,
                read=self._poll_wait_seconds + _POLL_TIMEOUT_MARGIN_SECONDS,
                write=self._read_timeout,
                pool=self._connect_timeout,
            ),
        )
        return ClaimResponse.model_validate(_unwrap(response))

    async def heartbeat(self, run_id: str, stage: str, request: HeartbeatRequest) -> HeartbeatAck:
        response = await self._send(
            "POST",
            f"{JOB_API_PREFIX}/{run_id}/stages/{stage}/heartbeat",
            json=_dump(request),
        )
        return HeartbeatAck.model_validate(_unwrap(response))

    async def complete(self, run_id: str, stage: str, result: StageResult) -> CompleteAck:
        response = await self._send(
            "POST",
            f"{JOB_API_PREFIX}/{run_id}/stages/{stage}/complete",
            json=_dump(result),
            headers={"Idempotency-Key": result.idempotency_key},
        )
        return CompleteAck.model_validate(_unwrap(response))

    async def download_input(self, run_id: str, storage_key: str, dest: Path) -> None:
        """입력 미디어를 내려받는다. 공유 볼륨이 없는 배포(RunPod)의 경로다."""
        request = self._client.build_request(
            "GET",
            f"{JOB_API_PREFIX}/{run_id}/artifacts",
            params={"key": storage_key},
            headers=self._artifact_headers(run_id),
        )
        try:
            response = await self._client.send(request, stream=True)
        except httpx2.TransportError as exc:
            msg = f"입력을 받아오지 못했다: {storage_key}"
            raise InputDownloadError(msg) from exc
        try:
            if response.status_code >= 400:
                # 스트리밍 응답이므로 **판정 전에 본문을 읽어야 한다.** 읽지 않은
                # 스트림에서 .json() 을 부르면 ResponseNotRead 가 나고, 그것은
                # StreamError(RuntimeError) 라 _error_code 의 except ValueError 에
                # 걸리지 않는다. 그대로 올라가면 classify() 가 단계별 기본값으로
                # 떨어뜨려 "미디어를 못 가져왔다" 가 "이 단계가 실패했다" 로 둔갑한다.
                await response.aread()
                if response.status_code == 404:
                    # 이 경로의 404 는 언제나 "입력이 없다" 다. 다시 받아도 없다.
                    msg = f"입력이 없다: {storage_key}"
                    raise InputUnavailableError(msg)
                try:
                    _raise_for_status(response)
                except JobApiUnavailableError as exc:
                    # 입력을 못 가져온 것이지 잡 API 일반 오류가 아니다. 단계 결과에
                    # 실리는 코드가 미디어 어휘여야 원인을 찾을 수 있다.
                    # 인증 거절은 _raise_for_status 가 먼저 갈라내 그대로 올라간다 —
                    # 토큰 문제는 단계 문제가 아니다.
                    msg = f"입력을 받아오지 못했다: {storage_key}"
                    raise InputDownloadError(msg) from exc
            try:
                with dest.open("wb") as handle:
                    async for chunk in response.aiter_bytes(_DOWNLOAD_CHUNK_BYTES):
                        handle.write(chunk)
            except httpx2.TransportError as exc:
                # 헤더 교환은 성공했으므로 위의 핸들러로는 잡히지 않는다. 수 GB 를
                # 받는 도중 끊기면 날 ReadError 가 올라가고, classify() 가 그것을
                # OSError 로도 보지 못해 단계별 기본값으로 떨어진다 — "미디어를 못
                # 가져왔다" 가 "이 단계가 실패했다" 로 기록되는 그 오분류다.
                msg = f"입력을 받는 중 연결이 끊겼다: {storage_key}"
                raise InputDownloadError(msg) from exc
        finally:
            await response.aclose()

    async def upload_artifact(
        self,
        run_id: str,
        storage_key: str,
        body: bytes,
        *,
        content_type: str,
        content_sha256: str,
    ) -> None:
        """산출물을 올린다. 계약이 교환하는 것은 언제나 storage key 이고 바이트는 여기로 흐른다."""
        safe_key = _safe_artifact_key(storage_key)
        try:
            for attempt in range(2):
                try:
                    await self._send(
                        "PUT",
                        f"{JOB_API_PREFIX}/{run_id}/artifacts/{safe_key}",
                        content=body,
                        headers={
                            **self._artifact_headers(run_id),
                            "Content-Type": content_type,
                            "X-Content-SHA256": content_sha256,
                        },
                    )
                    return
                except ArtifactHashMismatchError:
                    if attempt == 1:
                        raise
        except JobApiUnavailableError as exc:
            # 일시 실패만 업로드 실패로 감싼다. ArtifactKeyRejectedError 같은 영구
            # 오류를 여기서 삼키면 재시도 가능으로 둔갑한다.
            msg = f"산출물 업로드가 실패했다: {storage_key}"
            raise ArtifactUploadError(msg) from exc

    # ── 전송 ─────────────────────────────────────────────────────────

    async def _send(
        self,
        method: str,
        url: str,
        *,
        json: Any = None,
        content: bytes | None = None,
        headers: Mapping[str, str] | None = None,
        timeout: httpx2.Timeout | None = None,
    ) -> httpx2.Response:
        """한 요청을 보내고 일시 실패만 재시도한다.

        영구 실패(인증·fencing)는 즉시 올린다. 토큰이 거절되는데 백오프로 계속 두드리면
        BE 로그만 더럽히고 열리지는 않는다.
        """
        last_error: Exception | None = None
        for attempt in range(self._max_attempts):
            request = self._client.build_request(
                method,
                url,
                json=json,
                content=content,
                headers=dict(headers) if headers else None,
                timeout=timeout if timeout is not None else self._default_timeout,
            )
            try:
                response = await self._client.send(request)
            except httpx2.TransportError as exc:
                last_error = exc
                logger.warning("잡 API 전송 실패 (%s %s): %s", method, url, exc)
            else:
                if response.status_code < 400:
                    return response
                _raise_for_contract_error(response)
                if response.status_code not in _RETRYABLE_STATUS:
                    _raise_for_status(response)
                last_error = JobApiUnavailableError(f"{response.status_code} {method} {url}")
                logger.warning("잡 API %s (%s %s)", response.status_code, method, url)
                if attempt < self._max_attempts - 1:
                    await self._sleep_before_retry(attempt, response)
                continue
            if attempt < self._max_attempts - 1:
                # 마지막 시도 뒤에 자면 이미 확정된 실패를 그만큼 늦게 보고한다.
                # complete 에서 나면 워커가 끝난 결과를 든 채 lease 를 흘린다.
                await self._sleep_before_retry(attempt, None)

        msg = f"잡 API 호출이 {self._max_attempts}회 실패했다: {method} {url}"
        raise JobApiUnavailableError(msg) from last_error

    async def _sleep_before_retry(self, attempt: int, response: httpx2.Response | None) -> None:
        """full jitter 지수 백오프. `Retry-After` 가 있으면 그쪽을 존중한다.

        jitter 를 넣는 이유는 워커가 여럿이기 때문이다. 고정 간격이면 BE 가 잠깐 흔들린
        뒤 모든 워커가 같은 순간에 함께 몰려든다.
        """
        retry_after = _retry_after_seconds(response) if response is not None else None
        if retry_after is not None:
            delay = min(retry_after, self._max_backoff_seconds)
        else:
            ceiling = min(_BACKOFF_BASE_SECONDS * (2**attempt), self._max_backoff_seconds)
            delay = random.uniform(0, ceiling)
        await asyncio.sleep(delay)


# ── 응답 처리 ────────────────────────────────────────────────────────


def _safe_artifact_key(storage_key: str) -> str:
    """산출물 키를 URL 경로에 실을 수 있는 형태로 만든다.

    두 가지를 한다.

    1. **`..`·절대 경로를 거절한다.** 인코딩으로는 막히지 않는다 —
       `quote(key, safe="/")` 는 구분자를 남기므로 `runs/1/../../admin` 이
       `/api/v1/internal/jobs/1/admin` 으로 정규화된다(실측). 접두 밖 쓰기를 막는 것은
       계약이 BE 에 맡긴 일이지만(`JOB_403_001`), 요청이 아예 다른 엔드포인트로 가면
       그 검사가 돌지 않는다. 판정은 POSIX·Windows 양쪽 규칙으로 한다 — 같은 키가
       개발 머신과 리눅스 컨테이너에서 다르게 판정되면 안 된다.
    2. **`?`·`#` 를 인코딩한다.** 그대로 두면 httpx2 가 질의·프래그먼트로 갈라
       경로가 잘린다(실측). 공백·비ASCII 는 httpx2 가 이미 퍼센트 인코딩하므로
       여기서 하는 일이 아니다. 구분자 `/` 는 남긴다 — 계약이 키를 미디어 루트
       상대 **경로**로 정의한다(`docs/contracts/job-api.md` §4.4).
    """
    if _is_absolute_anywhere(storage_key) or _has_parent_segment(storage_key):
        msg = f"산출물 키가 경로를 벗어난다: {storage_key}"
        raise ArtifactKeyRejectedError(msg)
    return quote(storage_key, safe="/")


def _is_absolute_anywhere(key: str) -> bool:
    return PurePosixPath(key).is_absolute() or PureWindowsPath(key).is_absolute()


def _has_parent_segment(key: str) -> bool:
    """`..` 세그먼트가 있는지. POSIX·Windows 양쪽 규칙으로 본다.

    `PurePosixPath` 만 보면 `\\` 를 구분자로 읽지 않아
    `runs\\..\\admin.png` 가 통과한다. 같은 키가 개발 머신과 리눅스
    컨테이너에서 다르게 판정되면 안 된다.
    """
    return ".." in PurePosixPath(key).parts or ".." in PureWindowsPath(key).parts


def _dump(model: ClaimRequest | HeartbeatRequest | StageResult) -> dict[str, Any]:
    """와이어 모양으로 직렬화한다.

    `exclude_none` 을 쓰지 않는다. `modelVersion`/`promptVersion` 은 키가 있고 값만
    `null` 이어야 하는데, 그 옵션을 켜면 키가 통째로 사라진다.
    """
    return model.model_dump(by_alias=True, mode="json")


def _unwrap(response: httpx2.Response) -> Any:
    """BE 의 ApiResponse 봉투에서 data 를 꺼낸다.

    봉투는 `{isSuccess, code, message, timestamp, path, data}` 이고 NON_NULL 이라
    `data` 가 없을 수 있다. `isSuccess` 가 거짓이면 BE 의 code 를 그대로 보존해 올린다 —
    "실패했다" 보다 "어떤 코드로 실패했다" 가 진단에 쓸모 있다.
    """
    payload = response.json()
    if not isinstance(payload, dict):
        msg = f"잡 API 응답이 객체가 아니다: {type(payload).__name__}"
        raise JobApiUnavailableError(msg)
    if payload.get("isSuccess") is False:
        code = payload.get("code", "UNKNOWN")
        message = payload.get("message", "")
        msg = f"잡 API 가 실패를 돌려줬다: {code} {message}"
        raise JobApiUnavailableError(msg)
    return payload.get("data") or {}


#: 계약이 정한 오류 코드 → 워커 예외. 상태 코드만으로는 갈라지지 않는다.
#: 409 하나에 "이미 성공했다"(폐기가 정상)·"lease 를 잃었다"·"워커를 고쳐야 한다" 가
#: 함께 들어 있고, 403 에는 "이 단계만 실패" 와 "이 서버에서 일하면 안 된다" 가 있다.
_ERROR_BY_CODE: Final[Mapping[str, type[WorkerError]]] = {
    "JOB_400_001": JobApiInvalidRequestError,
    "JOB_400_002": ArtifactHashMismatchError,
    "JOB_411_001": JobApiInvalidRequestError,
    "JOB_403_001": ArtifactKeyRejectedError,
    "JOB_403_002": JobApiUnauthorizedError,
    "JOB_404_001": JobApiConflictError,
    "JOB_404_002": InputUnavailableError,
    "JOB_409_001": StageAlreadyCompletedError,
    "JOB_409_002": LeaseLostError,
    "JOB_409_003": JobApiConflictError,
    "JOB_409_004": JobApiConflictError,
}

#: 코드가 없거나 모를 때의 폴백. 보수적으로 잡는다 — 이해하지 못하는 충돌에서
#: 쓰기를 밀어붙이는 것보다 버리는 쪽이 안전하다.
_ERROR_BY_STATUS: Final[Mapping[int, type[WorkerError]]] = {
    400: JobApiInvalidRequestError,
    411: JobApiInvalidRequestError,
    401: JobApiUnauthorizedError,
    403: JobApiUnauthorizedError,
    409: JobApiConflictError,
}


def _raise_for_contract_error(response: httpx2.Response) -> None:
    """재시도로 풀리지 않는 응답을 계약이 정한 예외로 바꾼다."""
    code = _error_code(response)
    error = _ERROR_BY_CODE.get(code) or _ERROR_BY_STATUS.get(response.status_code)
    if error is None:
        return
    msg = f"잡 API 가 거절했다: {response.status_code} {code}"
    raise error(msg)


def _raise_for_status(response: httpx2.Response) -> None:
    _raise_for_contract_error(response)
    if response.status_code >= 400:
        msg = f"잡 API 오류 {response.status_code}: {_error_code(response)}"
        raise JobApiUnavailableError(msg)


def _error_code(response: httpx2.Response) -> str:
    """실패 응답의 BE 오류 코드. 본문이 봉투가 아닐 수도 있으므로 방어한다."""
    try:
        payload = response.json()
    except ValueError:
        return "UNPARSEABLE"
    if isinstance(payload, dict):
        code = payload.get("code")
        if isinstance(code, str):
            return code
    return "UNKNOWN"


def _retry_after_seconds(response: httpx2.Response) -> float | None:
    raw = response.headers.get("Retry-After")
    if raw is None:
        return None
    try:
        return max(0.0, float(raw))
    except ValueError:
        # HTTP-date 형식은 지원하지 않는다. 그 경우 기본 백오프로 떨어진다.
        return None
