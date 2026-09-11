"""입력 미디어를 로컬 경로로 만든다.

배포가 둘인데 계약은 하나여야 한다. compose 에서는 backend 와 ai-worker 가
`media:/srv/npick/media` 를 함께 마운트하므로 기가바이트짜리 원본을 HTTP 로 복사할 이유가
없다. RunPod 파드에는 공유 볼륨이 없고 아웃바운드 443 만 있다
(`docs/architecture/03-deployment.md`). 그래서 계약이 교환하는 것은 언제나 storage key 이고,
바이트가 실제로 어떻게 오는지는 배정의 `transport` 가 정한다. 코드는 한 벌이다.
"""

import hashlib
import logging
import tempfile
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Final, Literal

from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import (
    ContentHashMismatchError,
    InputDownloadError,
    InputUnavailableError,
)
from npick_worker.jobs.models import MediaRef

logger = logging.getLogger(__name__)

_HASH_CHUNK_BYTES: Final[int] = 1 << 20

#: 로그·오류 메시지에서 절대 경로를 대신할 표시. FRD 는 응답과 로그에 절대 경로를
#: 노출하지 않도록 요구한다.
_MEDIA_PLACEHOLDER: Final[str] = "<media>"


@dataclass(frozen=True, slots=True)
class ResolvedInput:
    path: Path
    source: Literal["shared_mount", "download"]


class MediaResolver:
    """배정의 입력 참조를 실제로 열 수 있는 경로로 바꾼다."""

    def __init__(self, media_root: Path | None, client: JobApiClient | None = None) -> None:
        self._media_root = media_root.resolve() if media_root is not None else None
        self._client = client

    @asynccontextmanager
    async def resolve(self, run_id: str, ref: MediaRef) -> AsyncIterator[ResolvedInput]:
        """입력을 쓸 수 있는 동안만 유효한 경로를 내준다.

        내려받은 파일은 블록을 빠져나갈 때 지운다. 원본은 기가바이트급이고 파드 디스크는
        휘발성이지만 한 파드가 잡을 여러 개 처리하므로, 남겨 두면 금방 찬다.
        """
        for key in (ref.storage_key, ref.local_path):
            if key is not None and (
                not key
                or _is_absolute_anywhere(key)
                or PureWindowsPath(key).drive
                or "\\" in key
                or ":" in key
                or any(part in {"", ".", ".."} for part in key.split("/"))
            ):
                raise InputUnavailableError(
                    "잘못된 형식·절대 경로·루트를 벗어난 미디어 키는 허용하지 않는다"
                )
        if ref.transport == "shared-volume":
            mounted = self._shared_path(ref)
            if mounted is not None:
                _verify_input(mounted, ref)
                yield ResolvedInput(path=mounted, source="shared_mount")
                return
            # 마운트가 안 붙었거나 파일이 없다. 계약 §5 는 http 를 필수, shared-volume
            # 을 선택(최적화)으로 두므로 여기서 포기하지 않는다 — 마운트 오타 하나가
            # fleet 전체를 재시도 없는 영구 실패로 만드는 것은 그 의도와 반대다.
            if self._client is None:
                msg = f"공유 마운트에서 입력을 찾지 못했고 내려받을 수단도 없다: {ref.storage_key}"
                raise InputUnavailableError(msg)
            logger.warning(
                "공유 마운트에서 입력을 찾지 못했다. HTTP 로 내려받는다: %s", ref.storage_key
            )

        if self._client is None:
            msg = f"내려받을 수단이 없다: {ref.storage_key}"
            raise InputUnavailableError(msg)

        with tempfile.TemporaryDirectory(prefix="npick-input-") as tmp:
            dest = Path(tmp) / Path(ref.storage_key).name
            await self._client.download_input(run_id, ref.storage_key, dest)
            _verify_input(dest, ref)
            yield ResolvedInput(path=dest, source="download")

    async def fetch_artifacts(
        self,
        run_id: str,
        storage_keys: Sequence[str],
        dest_dir: Path,
        *,
        transport: Literal["http", "shared-volume"],
    ) -> dict[str, Path]:
        """상류 단계가 올려 둔 산출물을 열 수 있는 경로로 바꾼다.

        `resolve()` 가 배정의 **입력 미디어** 하나를 푸는 것과 달리 이쪽은 상류
        산출물 여럿이다. 필요한 단계는 `ocr` 이다 — 읽을 대상이 영상이 아니라
        `frame_extraction` 이 올린 keyframe JPEG 이기 때문이다(FRD `docs/frd.md:131`
        "OCR 은 추출한 키프레임을 대상으로 수행한다").

        공유 마운트가 붙어 있으면 **복사하지 않는다.** keyframe 은 장면 수백 개에
        장 수를 곱한 만큼이고 compose 에서는 그 파일들이 이미 같은 볼륨에 있다.

        `resolve()` 와 다른 점이 둘이다.

        - **정리를 여기서 하지 않는다.** 호출부(`runner._run_stage`)가 잡마다 만드는
          작업 디렉터리 안에 받으므로 그 디렉터리와 함께 지워진다.
        - **크기·해시를 대조하지 않는다.** `inputs.upstream` 이 돌려주는
          `keyframe` 항목에는 `contentHash` 도 `sizeBytes` 도 없다(계약 §4.3.1 의
          출력 모양). 잘린 파일은 단계가 이미지로 열지 못해 실패로 드러나는 데
          그친다 — BE 가 상류 산출물에 해시를 실어 주면 여기서 막을 수 있다.
        """
        # **중복은 여기서 한 번 없앤다.** 아래 두 갈래(마운트·다운로드)가 각자 막으면
        # 한쪽만 걸린다 — `resolved` 를 보는 가드는 마운트에서 이미 푼 키만 걸러서,
        # http 갈래의 중복 키는 `to_download` 에 두 번 들어가 같은 파일을 두 번 받는다.
        # `_ocr_required_inputs` 가 먼저 없애 주므로 정상 입력에서는 도달하지 않지만,
        # 가드가 여기 있는 이상 여기서 맞아야 한다.
        unique_keys = list(dict.fromkeys(storage_keys))

        resolved: dict[str, Path] = {}
        to_download: list[str] = []

        for key in unique_keys:
            if transport == "shared-volume":
                mounted = self._shared_path(MediaRef(storage_key=key, transport=transport))
                if mounted is not None:
                    resolved[key] = mounted
                    continue
            to_download.append(key)

        if not to_download:
            return resolved

        if self._client is None:
            msg = f"상류 산출물을 내려받을 수단이 없다: {len(to_download)}건"
            raise InputUnavailableError(msg)
        if transport == "shared-volume":
            logger.warning(
                "공유 마운트에서 상류 산출물 %d건을 찾지 못했다. HTTP 로 내려받는다",
                len(to_download),
            )

        dest_dir.mkdir(parents=True, exist_ok=True)
        for index, key in enumerate(to_download):
            # 키를 파일 이름으로 쓰지 않는다. 두 scene 의 파일 이름이 같을 수 있고
            # (`kf-000004133.jpg`), 키를 그대로 경로로 이으면 BE 가 준 문자열이
            # 로컬 경로를 만드는 통로가 된다. 순번이면 둘 다 없다.
            dest = dest_dir / f"{index:05d}{Path(key).suffix}"
            await self._client.download_input(run_id, key, dest)
            resolved[key] = dest
        return resolved

    def _shared_path(self, ref: MediaRef) -> Path | None:
        """공유 마운트에서 파일을 찾는다. 마운트나 파일이 없으면 `None`.

        `storage_key` 는 BE 가 준 값이지만 검증 없이 경로로 이어 붙이면 잡 API 가
        임의 파일 읽기 통로가 된다. media 는 사용자 경로가 아니라 검증된 ID 로만
        접근한다는 요구가 코드에서 지켜지는 지점이 여기다.

        **키 거절과 "없음" 을 가른다.** 루트를 벗어난 키는 `None` 이 아니라 예외다 —
        `None` 을 돌려주면 호출부가 다운로드로 내려가 같은 키를 BE 에 보낸다.
        마운트·파일이 없는 것은 설정 문제이므로 폴백할 수 있다.
        """
        key = ref.local_path or ref.storage_key
        if _is_absolute_anywhere(key):
            msg = f"절대 경로 키는 허용하지 않는다: {key}"
            raise InputUnavailableError(msg)

        if self._media_root is None or not self._media_root.is_dir():
            return None

        candidate = (self._media_root / key).resolve()
        if not candidate.is_relative_to(self._media_root):
            msg = f"미디어 루트를 벗어난 키다: {key}"
            raise InputUnavailableError(msg)
        return candidate if candidate.is_file() else None


def _is_absolute_anywhere(key: str) -> bool:
    """POSIX 로도 Windows 로도 절대 경로가 아닌지 본다.

    `Path` 는 실행 중인 OS 의 규칙만 쓴다. Windows 에서 `Path("/etc/passwd")` 는
    절대 경로가 아니어서, 같은 키가 개발 머신과 리눅스 컨테이너에서 다른 검사에 걸린다.
    보안 검사가 플랫폼에 따라 달라지면 안 되므로 양쪽 규칙을 모두 본다.
    """
    return PurePosixPath(key).is_absolute() or PureWindowsPath(key).is_absolute()


def _verify_input(path: Path, ref: MediaRef) -> None:
    """받은 파일이 배정이 말한 것인지 본다. 크기를 먼저, 그다음 해시.

    **크기가 유일한 방어선인 경우가 있다.** 해시가 없으면 오류 없이 일찍 끝난 응답
    (프록시 타임아웃·BE 가 스트림 중간에 죽는 경우)이 부분 파일을 남기고 그대로
    정상 해석된다. 그러면 `detect_scenes` 가 잘린 mp4 를 돌려 틀린 scene 경계를
    `succeeded` 로 정본에 넣는다 — 깨끗한 실패보다 나쁘다. 아무도 그 run 을
    의심하지 않는다.
    """
    if ref.size_bytes is not None:
        actual_size = path.stat().st_size
        if actual_size != ref.size_bytes:
            # 잘린 응답은 **일시** 오류다. 다시 받으면 온전할 수 있다.
            msg = (
                f"받은 크기가 배정과 다르다: {ref.storage_key} "
                f"(기대 {ref.size_bytes}, 실제 {actual_size})"
            )
            raise InputDownloadError(msg)

    if ref.content_hash is None:
        return
    actual = sha256_file(path)
    if actual != _normalize_hash(ref.content_hash):
        # 해시가 다르면 다시 받아도 같은 파일이 온다. 일시 오류가 아니다.
        msg = (
            f"입력 해시가 배정과 다르다: {ref.storage_key} (기대 {ref.content_hash}, 실제 {actual})"
        )
        raise ContentHashMismatchError(msg)


def _normalize_hash(value: str) -> str:
    """표현 차이를 지운다. 대소문자·`sha256:` 접두·앞뒤 공백은 같은 해시다.

    계약 §4.1 이 `"contentHash": "3f1e…"` 로만 적어 인코딩을 못 박지 않았다. 여기서
    표현이 갈리면 `ContentHashMismatchError` 는 영구(`UNSUPPORTED_MEDIA`)이고 계약
    §9.2 가 영구를 "attempts 동결·재claim 없음" 으로 두므로, 치명 단계인
    `scene_detection` 이 이걸로 죽으면 run 이 **재시도 없이** failed 가 된다.
    Java 쪽이 `String.format("%02X")` 를 쓰기만 해도 전 잡이 그렇게 된다.
    """
    return value.strip().lower().removeprefix("sha256:")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(_HASH_CHUNK_BYTES):
            digest.update(chunk)
    return digest.hexdigest()


def redact(text: str, *, media_root: Path | None, extra: Path | None = None) -> str:
    """오류 메시지에서 절대 경로를 지운다.

    메시지는 `stage_states_json` 을 거쳐 검수자 화면까지 갈 수 있다. 서버의 디렉터리
    구조가 거기 실릴 이유가 없다.
    """
    result = text
    for root in (extra, media_root):
        if root is None:
            continue
        for form in {str(root), str(root.resolve()), root.as_posix()}:
            result = result.replace(form, _MEDIA_PLACEHOLDER)
    return result
