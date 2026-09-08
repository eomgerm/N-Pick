"""claim → 실행 → heartbeat → complete 루프.

워커가 발신자다. BE 는 잡을 밀어 넣지 않고 워커가 long-poll 로 받아 간다
(`docs/architecture/02-container.md`). 그래서 여기 있는 것은 서버가 아니라 루프다.

단계 재시도 횟수와 단계 타임아웃은 **구현하지 않는다**. 그 수치는 아직 동결되지 않았고
(`infra/compose/profiles/pipeline.yml` 에서 null), 시도 상한은 BE 디스패처가 소유한다.
워커는 결과를 정확히 보고할 뿐이다.
"""

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Final

from npick_worker.jobs import registry
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import (
    JobApiConflictError,
    JobApiError,
    JobApiUnauthorizedError,
    JobApiUnavailableError,
    LeaseLostError,
    StageAlreadyCompletedError,
    StageUnavailableError,
    UnknownStageError,
    WorkerError,
    classify,
)
from npick_worker.jobs.media import MediaResolver, redact
from npick_worker.jobs.models import (
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

#: 예상치 못한 오류 뒤에 쉬는 시간. 즉시 재발하는 오류에서 hot loop 가 되지 않게 한다.
_UNEXPECTED_ERROR_BACKOFF_SECONDS: Final[float] = 5.0

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
            except JobApiUnavailableError as exc:
                # 클라이언트가 이미 백오프하며 재시도했다. 여기서 또 자지 않는다.
                logger.warning("잡 API 에 닿지 못했다: %s", exc)
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

    # ── 잡 하나 ──────────────────────────────────────────────────────

    async def _process(self, job: JobAssignment, lease: LeaseGrant) -> None:
        """배정 하나를 처리하고, BE 가 다음 단계를 실어 보내면 이어서 처리한다.

        `next` 를 무시하면 BE 가 배정한 lease 가 실행도 heartbeat 도 없이 만료된다.
        """
        while True:
            control = _JobControl(abandoned=asyncio.Event())
            heartbeat = asyncio.create_task(
                self._heartbeat_loop(job, lease, control), name=f"heartbeat:{job.pipeline_run_id}"
            )
            try:
                result = await self._execute(job, lease)

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

    async def _execute(self, job: JobAssignment, lease: LeaseGrant) -> StageResult:
        """단계를 돌리고 결과를 봉투로 만든다. 이 함수는 예외를 올리지 않는다.

        실패도 결과다. 예외로 빠져나가면 BE 는 lease 가 만료될 때까지 아무것도 모른다.
        """
        started_at = datetime.now(UTC)
        started = time.monotonic()

        try:
            outcome = await self._run_stage(job)
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
            artifacts=outcome.artifacts,
        )

    async def _run_stage(self, job: JobAssignment) -> StageOutcome:
        if job.stage not in STAGES_BY_NAME:
            msg = f"FRD 단계 표에 없는 이름이다: {job.stage}"
            raise UnknownStageError(msg)

        handler = registry.resolve(job.stage)
        if handler is None:
            msg = f"이 워커에 구현이 없다: {job.stage}"
            raise StageUnavailableError(msg)

        async with self._media.resolve(job.pipeline_run_id, job.inputs.media) as resolved:
            context = StageContext(
                stage=job.stage,
                video_path=resolved.path,
                storage_key=job.inputs.media.storage_key,
                params=job.inputs.config,
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
        """실패한 단계의 버전. 실제 재현 식별자를 만들 수 없었음을 그대로 적는다.

        아는 척하지 않는다. 여기에 그럴듯한 값을 넣으면 "이 버전으로 돌렸는데 실패했다"
        는 잘못된 기록이 남는다.
        """
        declared = registry.capability_versions().get(job.stage)
        return StageVersion(
            stage_version=declared or _UNKNOWN_VERSION,
            output_schema_version=(
                job.output_schema_version
                or (
                    output_schema_version(job.stage)
                    if job.stage in STAGES_BY_NAME
                    else _UNKNOWN_VERSION
                )
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
