"""claim → 실행 → heartbeat → complete 루프.

워커가 발신자다. BE 는 잡을 밀어 넣지 않고 워커가 long-poll 로 받아 간다
(`docs/architecture/02-container.md`). 그래서 여기 있는 것은 서버가 아니라 루프다.

단계 재시도 횟수와 단계 타임아웃은 **구현하지 않는다**. 그 수치는 아직 동결되지 않았고
(`infra/compose/profiles/pipeline.yml` 에서 null), 시도 상한은 BE 디스패처가 소유한다.
워커는 결과를 정확히 보고할 뿐이다.
"""

import asyncio
import hashlib
import logging
import tempfile
import time
import uuid
from collections.abc import Mapping
from contextlib import AsyncExitStack
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Final

from npick_worker.jobs import registry
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import (
    ArtifactKeyRejectedError,
    ArtifactUploadError,
    JobApiConflictError,
    JobApiError,
    JobApiInvalidRequestError,
    JobApiUnauthorizedError,
    JobApiUnavailableError,
    LeaseLostError,
    StageAlreadyCompletedError,
    StageConfigUnsupportedError,
    StageUnavailableError,
    UnknownStageError,
    WorkerError,
    classify,
)
from npick_worker.jobs.media import MediaResolver, redact
from npick_worker.jobs.models import (
    ArtifactRef,
    ClaimRequest,
    HeartbeatRequest,
    JobAssignment,
    LeaseGrant,
    StageCapability,
    StageError,
    StageResult,
    StageStatus,
    WorkerDevice,
    WorkerIdentity,
)
from npick_worker.jobs.registry import StageContext, StageOutcome
from npick_worker.jobs.versions import StageVersion, output_schema_version
from npick_worker.settings import Settings
from npick_worker.stages import STAGES_BY_NAME
from npick_worker.versioning import service_version

logger = logging.getLogger(__name__)

#: 상류 산출물을 받아 두는 작업 디렉터리 하위 폴더. 단계가 쓰는 산출물과
#: 섞이지 않게 나눈다 — `frame_extraction` 은 같은 work_dir 에 JPEG 을 쓴다.
_UPSTREAM_INPUT_DIR: Final[str] = "inputs"

#: 예상치 못한 오류 뒤에 쉬는 시간. 즉시 재발하는 오류에서 hot loop 가 되지 않게 한다.
_UNEXPECTED_ERROR_BACKOFF_SECONDS: Final[float] = 5.0

#: 잡 API 에 닿지 못했을 때의 최소 수면. `_send` 소진 경로는 이미 백오프했지만
#: `_unwrap` 의 봉투 실패(`200 {"isSuccess": false}`)는 재시도도 수면도 없이 올라온다.
#: 그 경로에 하한이 없으면 워커가 /claim 을 무제한 두드린다.
_UNAVAILABLE_MIN_BACKOFF_SECONDS: Final[float] = 1.0

#: 실패·생략을 보고할 때 쓰는 자리표시자 버전. 단계를 돌리지 못했으므로 실제 재현
#: 식별자가 없다. 봉투는 versions 를 요구하므로 "확인되지 않았다" 를 명시적으로 적는다.
_UNKNOWN_VERSION: Final[str] = "unknown"


def _mount_is_usable(media_root: Path | None) -> bool:
    """공유 마운트를 **선언해도 되는지**. 설정만으로 판단하지 않는다.

    `compose.yaml` 이 `NPICK_AI_MEDIA_ROOT` 를 무조건 주입하므로, 볼륨이 안 붙거나
    경로가 어긋나도 설정값은 그대로 있다. 그 상태로 `sharedMediaVolume: true` 를
    선언하면 BE 는 계속 `transport: shared-volume` 을 내려주고 워커는 매 잡을
    떨어뜨린다. 계약 §5 는 `http` 를 필수, `shared-volume` 을 선택(최적화)으로
    두므로 마운트 오타 하나가 fleet 전체를 실패시키는 것은 그 의도와 반대다.
    """
    if media_root is None:
        return False
    if media_root.is_dir():
        return True
    logger.warning(
        "NPICK_AI_MEDIA_ROOT 가 디렉터리가 아니다. 공유 마운트를 선언하지 않고 "
        "입력을 HTTP 로 받는다: %s",
        media_root,
    )
    return False


def generate_worker_id() -> str:
    """설정에 없을 때 쓸 워커 식별자.

    부르는 곳이 여럿이면 프로세스 하나가 서로 다른 ID 를 말하게 된다. 합성 지점
    (app.lifespan)에서 한 번만 부르고 클라이언트와 러너에 같은 값을 넘긴다.
    """
    return f"npick-ai-worker-{uuid.uuid4().hex[:8]}"


@dataclass(slots=True)
class _JobControl:
    """heartbeat 태스크가 실행부에 중단을 알리는 통로.

    실행 중인 단계를 멈추지는 못한다 — 단계는 취소 콜백이 없는 순수 함수이고
    별도 스레드에서 돌기 때문이다. 할 수 있는 것은 **결과를 버리는 것**이고
    계약이 요구하는 것도 그것이다. 반납해 버리면 BE 가 이미 다른 워커에 재배정한
    단계 위에 덮어쓴다.
    """

    abandoned: asyncio.Event
    reason: str = ""

    def abandon(self, reason: str) -> None:
        self.reason = reason
        self.abandoned.set()


@dataclass(frozen=True, slots=True)
class _Timing:
    started_at: datetime
    finished_at: datetime
    duration_ms: int


class JobRunner:
    """잡 하나를 끝까지 처리하는 루프. 상태는 갖지 않는다."""

    def __init__(
        self,
        *,
        client: JobApiClient,
        media: MediaResolver,
        worker_id: str,
        fleet: str,
        poll_wait_seconds: int,
        heartbeat_seconds: float,
        shared_media_volume: bool,
        media_root: Path | None,
        device: WorkerDevice,
    ) -> None:
        self._client = client
        self._media = media
        self._worker_id = worker_id
        self._fleet = fleet
        self._poll_wait_seconds = poll_wait_seconds
        self._heartbeat_seconds = heartbeat_seconds
        self._shared_media_volume = shared_media_volume
        self._media_root = media_root
        self._device = device
        self._instance_id = uuid.uuid4().hex

    @property
    def worker_id(self) -> str:
        """claim 본문에 싣는 ID. 클라이언트가 헤더에 싣는 값과 같아야 한다."""
        return self._worker_id

    @classmethod
    def from_settings(
        cls, settings: Settings, client: JobApiClient, *, worker_id: str
    ) -> "JobRunner":
        """`worker_id` 는 필수다. 여기서 만들면 클라이언트의 값과 갈라진다."""
        from npick_worker.device import detect_device

        info = detect_device(settings.device)
        return cls(
            client=client,
            media=MediaResolver(settings.media_root, client),
            worker_id=worker_id,
            fleet=settings.job_fleet,
            poll_wait_seconds=settings.job_poll_wait_seconds,
            heartbeat_seconds=settings.job_heartbeat_seconds,
            shared_media_volume=_mount_is_usable(settings.media_root),
            media_root=settings.media_root,
            device=WorkerDevice(
                kind=info.resolved,
                gpu_model=info.device_name,
                gpu_count=1 if info.cuda_available else 0,
                vram_mb=info.total_memory_mb,
                cuda_version=info.cuda_version,
                torch_version=info.torch_version,
            ),
        )

    # ── 루프 ─────────────────────────────────────────────────────────

    async def run(self) -> None:
        """취소될 때까지 돈다. 인증 거절이면 스스로 멈춘다."""
        while True:
            try:
                await self.run_once()
            except JobApiUnauthorizedError:
                logger.error("잡 API 인증이 거절됐다. 폴링을 멈춘다.")
                return
            except JobApiInvalidRequestError:
                logger.exception("잡 API 요청 계약이 거절됐다. 워커 수정 전까지 폴링을 멈춘다.")
                return
            except JobApiUnavailableError as exc:
                # `_send` 소진 경로는 이미 백오프했으니 여기서 오래 자지 않는다.
                # 다만 `_unwrap` 의 봉투 실패는 재시도도 수면도 없이 여기로 오므로
                # 짧은 하한을 둔다 — 그것이 없으면 유휴 루프에 제동이 없다.
                logger.warning("잡 API 에 닿지 못했다: %s", exc)
                await asyncio.sleep(_UNAVAILABLE_MIN_BACKOFF_SECONDS)
            except asyncio.CancelledError:
                raise
            except Exception:
                # 여기까지 오는 것은 계약을 벗어난 응답(ClaimResponse 검증 실패)이나
                # 러너 자체의 버그다. 그대로 나가면 lifespan 이 띄운 태스크가 조용히
                # 끝나고, 아무도 그 태스크를 await 하지 않으므로 예외가 어디에도
                # 보고되지 않는다. 컨테이너는 healthy 인데 잡을 하나도 안 가져간다.
                #
                # CancelledError 는 BaseException 이라 여기 걸리지 않는다 — 종료는
                # 위의 분기가 그대로 처리한다.
                logger.exception("잡 루프에서 예상치 못한 오류가 났다. 계속 폴링한다.")
                await asyncio.sleep(_UNEXPECTED_ERROR_BACKOFF_SECONDS)

    async def run_once(self) -> bool:
        """잡을 하나 받아 처리한다. 배정이 없으면 False.

        빈 응답에서 쉬지 않는 것이 중요하다 — 대기는 이미 서버가 25초 했다.
        """
        response = await self._client.claim(self._claim_request())
        if not response.assigned or response.job is None or response.lease is None:
            if response.retry_after_ms:
                await asyncio.sleep(response.retry_after_ms / 1000)
            return False

        await self._process(response.job, response.lease)
        return True

    def _claim_request(self) -> ClaimRequest:
        return ClaimRequest(
            worker=WorkerIdentity(
                worker_id=self._worker_id,
                instance_id=self._instance_id,
                fleet=self._fleet,
                worker_version=service_version(),
                max_concurrent_stages=1,
                shared_media_volume=self._shared_media_volume,
            ),
            capabilities=[
                StageCapability(stage=name, stage_version=version)
                for name, version in registry.capability_versions().items()
            ],
            device=self._device,
            wait_seconds=self._poll_wait_seconds,
        )
        # heldLeases 를 싣지 않는 이유 — maxConcurrentStages 가 1 이라 claim 하는
        # 순간 이 워커가 든 lease 가 없다. 그래서 계약 §4.1 의 좀비 절단(BE 가
        # revokedLeases 로 되돌려 주는 것)은 지금 구조에서 항상 빈 값이다.
        # maxConcurrentStages > 1 이 될 때 배선한다. 배선된 줄 착각하지 말 것.

    # ── 잡 하나 ──────────────────────────────────────────────────────

    async def _process(self, job: JobAssignment, lease: LeaseGrant) -> None:
        """배정 하나를 처리하고, BE 가 다음 단계를 실어 보내면 이어서 처리한다.

        `next` 를 무시하면 BE 가 배정한 lease 가 실행도 heartbeat 도 없이 만료된다.
        """
        while True:
            control = _JobControl(abandoned=asyncio.Event())
            self._client.bind_artifact_lease(job.pipeline_run_id, lease.lease_id)
            heartbeat = asyncio.create_task(
                self._heartbeat_loop(job, lease, control), name=f"heartbeat:{job.pipeline_run_id}"
            )
            try:
                result = await self._execute(job, lease, control)

                if control.abandoned.is_set():
                    logger.warning(
                        "중단된 작업의 결과를 버린다 (%s): %s/%s",
                        control.reason,
                        job.pipeline_run_id,
                        job.stage,
                    )
                    return

                # **반납도 heartbeat 안에서 한다.** `_send` 는 complete 에 5회 시도를
                # 주고 각 시도의 read timeout 이 30초라 최악 ~165초인데 lease TTL 은
                # 60초다. 계약 §4.2 가 "연장하는 것은 heartbeat 뿐" 이므로, 취소를
                # 여기 앞에 두면 BE 가 느릴 때 반납 도중 lease 가 만료되고 이미 쓴
                # GPU 시간을 통째로 버린다.
                ack = await self._client.complete(job.pipeline_run_id, job.stage, result)
            except LeaseLostError:
                logger.warning("lease 를 잃어 결과를 버린다: %s/%s", job.pipeline_run_id, job.stage)
                return
            except (StageAlreadyCompletedError, JobApiConflictError) as exc:
                logger.warning(
                    "결과를 반납하지 못했다 (%s): %s/%s",
                    exc.error_code,
                    job.pipeline_run_id,
                    job.stage,
                )
                return
            finally:
                self._client.release_artifact_lease(job.pipeline_run_id)
                heartbeat.cancel()
                # `gather` 가 예외를 값으로 돌려주므로 finally 에서 되던지지 않는다.
                # `suppress(CancelledError)` 로는 부족했다 — heartbeat 태스크가 다른
                # 예외를 든 채 이미 끝났으면 cancel() 이 무효이고 await 가 그것을
                # 여기서 올려 `try` 의 예외까지 가린다.
                await asyncio.gather(heartbeat, return_exceptions=True)

            nxt = ack.next
            if nxt is None or not nxt.assigned or nxt.job is None or nxt.lease is None:
                return
            job, lease = nxt.job, nxt.lease

    async def _heartbeat_loop(
        self, job: JobAssignment, lease: LeaseGrant, control: _JobControl
    ) -> None:
        """lease 를 살려 두고, 중단 사유가 생기면 실행부에 알린다.

        실행이 블로킹 작업이라 별도 태스크로 돈다. 알리기만 하고 멈추지는 못한다.
        """
        interval = self._heartbeat_interval(lease)
        started = time.monotonic()
        while True:
            await asyncio.sleep(interval)
            elapsed_ms = int((time.monotonic() - started) * 1000)
            try:
                ack = await self._client.heartbeat(
                    job.pipeline_run_id,
                    job.stage,
                    HeartbeatRequest(lease_id=lease.lease_id, elapsed_ms=elapsed_ms),
                )
            except JobApiUnavailableError as exc:
                # BE 가 잠깐 흔들린 것이다. lease 는 아직 내 것이므로 계속 뛴다.
                logger.warning("heartbeat 실패: %s", exc)
                continue
            except JobApiError as exc:
                # LeaseLostError·StageAlreadyCompletedError·JobApiConflictError·
                # JobApiUnauthorizedError 를 한 어휘로 받는다. 모두 "이 단계를 계속할
                # 이유가 없다" 이고, 계약 §4.2 는 STAGE_ALREADY_COMPLETED 를 정당한
                # abort 사유로 열거하므로 예상 밖 상황도 아니다.
                #
                # **태스크 밖으로 내보내면 안 된다.** join 이 그것을 finally 에서
                # 되던져 이미 만든 정상 결과를 버리고, heartbeat 의 403 하나가
                # run() 까지 올라가 폴링을 영구 정지시킨다.
                logger.warning(
                    "heartbeat 가 중단을 알렸다 (%s): %s", exc.error_code, job.pipeline_run_id
                )
                control.abandon(exc.error_code)
                return
            except Exception:
                # heartbeat 는 결과를 만들지 않는다. 여기서 나는 예외는 폐기 사유일
                # 뿐이고, 태스크 밖으로 나가면 위와 같은 일이 벌어진다.
                logger.exception("heartbeat 에서 예상치 못한 오류가 났다: %s", job.pipeline_run_id)
                control.abandon("heartbeat 오류")
                return
            if ack.command == "abort":
                logger.warning("BE 가 중단을 지시했다 (%s)", ack.abort_reason)
                control.abandon(ack.abort_reason or "abort")
                return

    def _heartbeat_interval(self, lease: LeaseGrant) -> float:
        """서버가 준 주기를 쓰되 설정한 상한을 넘지 않는다.

        BE 가 lease TTL 보다 긴 주기를 주면 워커는 그 사이 heartbeat 를 한 번도 보내지
        못하고 lease 가 만료된다. 상한이 그 사고를 막는다.

        `heartbeat_interval_ms` 는 `Field(gt=0)`(`models.py`)이라 0 이하가 들어올 수 없다.
        "서버가 안 주면 폴백" 분기를 두면 그 분기는 실행되지 않는다.
        """
        return min(lease.heartbeat_interval_ms / 1000, self._heartbeat_seconds)

    # ── 단계 실행 ────────────────────────────────────────────────────

    async def _execute(
        self, job: JobAssignment, lease: LeaseGrant, control: _JobControl
    ) -> StageResult:
        """단계를 돌리고 산출물을 올린 뒤 결과를 봉투로 만든다. 예외를 올리지 않는다.

        실패도 결과다. 예외로 빠져나가면 BE 는 lease 가 만료될 때까지 아무것도 모른다.

        업로드가 여기 있는 이유는 계약 §4.2 다 — "연장하는 것은 heartbeat 뿐" 이고
        artifacts 업로드는 lease 를 연장하지 않는다. 이 함수는 heartbeat 태스크가 도는
        동안 호출되므로 업로드가 오래 걸려도 lease 가 살아 있다. 반납(`complete`) 뒤로
        미루면 BE 가 이미 keyframe 행을 만든 뒤에 파일이 올라가고, 그 사이 조회는 없는
        파일을 가리킨다.
        """
        started_at = datetime.now(UTC)
        started = time.monotonic()

        try:
            # 단계가 파일을 쓸 자리. 잡마다 새로 만들고 나갈 때 지운다 — 파드 디스크는
            # 휘발성이지만 한 파드가 잡을 여러 개 처리하므로 남겨 두면 금방 찬다
            # (media.py 의 입력 임시 디렉터리와 같은 이유).
            with tempfile.TemporaryDirectory(prefix="npick-stage-") as work:
                work_dir = Path(work)
                outcome = await self._run_stage(job, work_dir)
                uploaded = await self._upload(job, outcome, control, work_dir)
        except (LeaseLostError, JobApiUnauthorizedError):
            # 단계의 실패가 아니라 제어 흐름이다. 결과 봉투로 바꾸면 "이 단계가
            # 실패했다" 는 잘못된 기록이 정본에 남는다.
            raise
        except Exception as exc:  # 실패를 보고해야 하므로 나머지는 전부 잡는다
            return self._failure_result(job, lease, exc, self._timing(started_at, started))

        timing = self._timing(started_at, started)
        return StageResult(
            lease_id=lease.lease_id,
            idempotency_key=job.idempotency_key,
            stage=job.stage,
            attempt=job.attempt,
            status="succeeded",
            started_at=timing.started_at,
            finished_at=timing.finished_at,
            duration_ms=timing.duration_ms,
            versions=outcome.versions,
            metrics=outcome.metrics,
            output=outcome.output,
            artifacts=(*outcome.artifacts, *uploaded),
        )

    async def _upload(
        self,
        job: JobAssignment,
        outcome: StageOutcome,
        control: _JobControl,
        work_dir: Path,
    ) -> tuple[ArtifactRef, ...]:
        """단계가 만든 파일을 올리고 그 참조를 돌려준다.

        중단된 작업이면 올리지 않는다. 파일 자체는 attempt 가 접두에 들어 있어
        (`runs/{runId}/{stage}/a{attempt}/`) 재배정된 워커의 것과 섞이지 않지만, 버릴
        결과의 바이트를 굳이 네트워크로 보낼 이유가 없다.

        **파일마다 다시 본다.** 중단은 첫 파일을 보내는 동안에도 온다 — heartbeat 는
        별도 태스크이고 업로드마다 await 가 있으므로 그 사이에 알린다. 진입 전에 한 번만
        보면 그 신호를 놓쳐 장면 수백 장을 끝까지 올리고, 결과는 어차피 버려지므로
        그 전송은 전부 헛일이며 그동안 이 워커는 다음 잡을 잡지 못한다.
        """
        if not outcome.uploads:
            return ()

        refs: list[ArtifactRef] = []
        # Validate the entire upload set before transmitting the first artifact.
        for upload in outcome.uploads:
            self._check_output_key(job, upload.ref.storage_key)
            if not upload.local_path.resolve().is_relative_to(work_dir.resolve()):
                raise ArtifactKeyRejectedError("upload source is outside stage work directory")
            body = upload.local_path.read_bytes()
            if (
                len(body) != upload.ref.byte_size
                or hashlib.sha256(body).hexdigest() != upload.ref.content_hash
            ):
                raise ArtifactKeyRejectedError("artifact size or hash differs from reported output")
        for upload in outcome.uploads:
            if control.abandoned.is_set():
                logger.warning(
                    "중단된 작업의 산출물 %d개를 올리지 않는다 (%s): %s/%s",
                    len(outcome.uploads) - len(refs),
                    control.reason,
                    job.pipeline_run_id,
                    job.stage,
                )
                return ()
            self._check_output_key(job, upload.ref.storage_key)
            try:
                body = upload.local_path.read_bytes()
            except OSError as exc:
                # 단계가 만들었다고 보고한 파일이 없다. 반쯤 올라간 산출물로 성공을
                # 보고하면 BE 는 keyframe 이 원래 그만큼인 줄 안다.
                msg = redact(
                    f"올릴 산출물을 읽지 못했다: {upload.ref.storage_key} ({exc.strerror})",
                    media_root=self._media_root,
                    extra=work_dir,
                )
                raise ArtifactUploadError(msg) from exc
            await self._client.upload_artifact(
                job.pipeline_run_id,
                upload.ref.storage_key,
                body,
                content_type=upload.content_type,
                content_sha256=upload.ref.content_hash,
            )
            # 올린 파일은 여기서 지운다. **피크를 낮추지는 못한다** — 단계가 그 클립의
            # keyframe 전부를 쓴 뒤에야 이 반복이 시작하므로 최대 사용량은 그대로다.
            # 줄어드는 것은 점유 시간이고, 뒤쪽 파일을 올리는 동안 앞쪽이 디스크를 잡고
            # 있지 않게 된다. 실패해도 attempt N+1 은 새 접두를 받으므로 부작용이 없다.
            upload.local_path.unlink(missing_ok=True)
            refs.append(upload.ref)
        logger.info("산출물 %d개를 올렸다: %s/%s", len(refs), job.pipeline_run_id, job.stage)
        return tuple(refs)

    @staticmethod
    def _check_output_key(job: JobAssignment, storage_key: str) -> None:
        """키가 배정이 준 접두 안인지 본다.

        BE 도 `JOB_403_001` 로 막지만(계약 §5), 그건 첫 파일을 이미 보낸 뒤다. 워커
        버그를 네트워크 왕복 없이 여기서 잡는다. 접두 밖 키는 재시도가 고치지 못하는
        **워커의 문제**이므로 권한 오류가 아니라 단계 실패로 신고한다.
        """
        from npick_worker.jobs.artifacts import validate_key

        try:
            validate_key(storage_key)
        except ValueError as exc:
            raise ArtifactKeyRejectedError("invalid artifact storageKey") from exc
        if not storage_key.startswith(job.output_key_prefix.rstrip("/") + "/"):
            msg = f"산출물 키가 배정이 준 접두 밖이다: {storage_key} (접두 {job.output_key_prefix})"
            raise ArtifactKeyRejectedError(msg)

    async def _run_stage(self, job: JobAssignment, work_dir: Path) -> StageOutcome:
        from npick_worker.jobs.artifacts import resolve_transcripts

        if job.inputs.config:
            # 계약 §4.1 이 `"config": {}` 이므로 지금 오는 일이 없다. 실제로 쓰는
            # 단계가 생길 때까지는 조용히 무시하는 것보다 거절하는 편이 정직하다 —
            # 무시하면 보고되는 stageVersion 이 기본 설정에서 계산된 값이라
            # "이 설정으로 만든 결과" 라는 거짓 기록이 남는다.
            msg = f"이 워커는 단계 설정을 쓰지 않는다: {sorted(job.inputs.config)}"
            raise StageConfigUnsupportedError(msg)

        if job.stage not in STAGES_BY_NAME:
            msg = f"FRD 단계 표에 없는 이름이다: {job.stage}"
            raise UnknownStageError(msg)

        handler = registry.resolve(job.stage)
        if handler is None:
            msg = f"이 워커에 구현이 없다: {job.stage}"
            raise StageUnavailableError(msg)

        async with AsyncExitStack() as stack:
            # **쓰지 않을 영상을 받지 않는다.** `transport: "http"` 에서 `resolve` 는
            # 원본 전체를 내려받는데, `ocr` 은 상류가 올린 keyframe 만 읽는다.
            # shared-volume 이면 어차피 복사가 없지만 RunPod 는 http 다(계약 §5).
            video_path: Path | None = None
            if handler.needs_video:
                resolved = await stack.enter_async_context(
                    self._media.resolve(job.pipeline_run_id, job.inputs.media)
                )
                video_path = resolved.path

            # 상류 산출물이 필요한 단계(`ocr`)는 여기서 받는다. `_execute` 가
            # heartbeat 태스크가 도는 동안 이 함수를 부르므로, keyframe 수백 장을
            # 받아도 lease 가 살아 있다 — 업로드를 여기 둔 것과 같은 이유다(계약 §4.2).
            upstream_files: Mapping[str, Path] = {}
            if handler.required_inputs is not None:
                keys = handler.required_inputs(job.inputs.upstream)
                if keys:
                    upstream_files = await self._media.fetch_artifacts(
                        job.pipeline_run_id,
                        keys,
                        work_dir / _UPSTREAM_INPUT_DIR,
                        transport=job.inputs.media.transport,
                    )

            # Transcript documents are independent of video/keyframe requirements.
            documents = await resolve_transcripts(job, self._media)
            context = StageContext(
                stage=job.stage,
                video_path=video_path,
                storage_key=job.inputs.media.storage_key,
                work_dir=work_dir,
                output_key_prefix=job.output_key_prefix,
                upstream=job.inputs.upstream,
                upstream_files=upstream_files,
                params=job.inputs.config,
                artifact_documents=documents,
            )
            # 단계는 블로킹 CPU 작업이다. 스레드로 넘겨야 heartbeat 가 계속 뛴다.
            return await asyncio.to_thread(handler.run, context)

    def _failure_result(
        self,
        job: JobAssignment,
        lease: LeaseGrant,
        exc: BaseException,
        timing: _Timing,
    ) -> StageResult:
        code, retryable = classify(exc, job.stage)
        # 구현이 없는 것은 실패가 아니라 생략이다. BE 의 stage_states_json 이
        # succeeded/failed/skipped 를 구분하고, 비치명 단계의 생략은 run 을 멈추지 않는다.
        status: StageStatus = "skipped" if code == "NO_ADAPTER" else "failed"
        message = redact(str(exc) or type(exc).__name__, media_root=self._media_root)
        if not isinstance(exc, WorkerError):
            logger.exception("단계 실행이 실패했다 (%s)", job.stage)

        return StageResult(
            lease_id=lease.lease_id,
            idempotency_key=job.idempotency_key,
            stage=job.stage,
            attempt=job.attempt,
            status=status,
            started_at=timing.started_at,
            finished_at=timing.finished_at,
            duration_ms=timing.duration_ms,
            versions=self._failure_versions(job),
            output=None,
            error=StageError(code=code, retryable=retryable, message=message),
        )

    def _failure_versions(self, job: JobAssignment) -> StageVersion:
        """실패한 단계의 버전.

        실행 가능한 단계가 런타임에 실패한 경우는 **선언된 stage_version 을 그대로
        싣는다** — 그 버전으로 돌렸다가 실패한 것이 맞으므로 그쪽이 정확한 기록이다.
        선언조차 없는 단계(구현 없음·이름 미확인)만 "unknown" 으로 남긴다. 그럴듯한
        값을 지어내지 않는다는 것이 요점이다.

        `output_schema_version` 도 같은 규칙으로 **워커 자신의 값**을 싣는다. 배정값을
        우선하면 BE 가 아직 v1 을 배정하는 단계(`ocr`·`vlm_metadata`)에서 실패는 v1,
        성공은 v2 가 되어 같은 잡의 두 결과가 다른 스키마를 주장한다. 워커가 무엇을
        낼 수 있는지는 워커가 아는 사실이므로 배정이 그것을 덮어쓰게 두지 않는다.
        배정과 어긋나는 것은 BE 가 판정할 문제이고 §11 항목 12 가 그 자리다.

        **`NPICK_AI_JOB_STAGES` 로 좁힌 워커에는 셋째 갈래가 생겼다** — 구현이 있는데
        선언하지 않은 단계다. 그런 단계가 여기 오면 "unknown" 이 된다. 지금은 도달할 수
        없다(BE 가 capabilities 로 거르고 `complete` 의 `next` 는 항상 null 이다). 하지만
        `_process` 의 체이닝이 켜지면 좁힌 워커가 선언하지 않은 단계를 실행하게 되고,
        그때는 실행한 버전을 알면서 "unknown" 을 보내는 셈이다. 체이닝을 켜는 쪽이
        배정 필터를 여기까지 이어야 한다.
        """
        declared = registry.capability_versions().get(job.stage)
        return StageVersion(
            stage_version=declared or _UNKNOWN_VERSION,
            output_schema_version=(
                output_schema_version(job.stage)
                if job.stage in STAGES_BY_NAME
                else (job.output_schema_version or _UNKNOWN_VERSION)
            ),
        )

    @staticmethod
    def _timing(started_at: datetime, started: float) -> _Timing:
        """처리 시간은 단조 시계로 잰다. 벽시계는 NTP 보정에 흔들린다."""
        duration_ms = int((time.monotonic() - started) * 1000)
        return _Timing(
            started_at=started_at,
            finished_at=datetime.now(UTC),
            duration_ms=duration_ms,
        )
