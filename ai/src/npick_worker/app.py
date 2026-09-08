"""FastAPI 앱.

**HTTP 표면은 헬스·운영용이다.** 잡 수신은 반대 방향이다 — 워커가 BE 의
claim/heartbeat/complete/artifacts 를 호출한다(`docs/contracts/job-api.md`).
그래서 잡 루프는 라우트가 아니라 lifespan 태스크로 돈다. 인바운드 잡 엔드포인트를
추가하지 않는다.
"""

import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator
from dataclasses import asdict

from fastapi import APIRouter, FastAPI

from npick_worker.device import detect_device
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.registry import WarmupReport, warm_up
from npick_worker.jobs.runner import JobRunner, generate_worker_id
from npick_worker.schemas import (
    DeviceStatus,
    HealthResponse,
    JobPollingStatus,
    PipelineRegistry,
    StageSummary,
    WarmStageStatus,
    WarmupStatus,
)
from npick_worker.settings import Settings, get_settings
from npick_worker.stages import STAGES
from npick_worker.versioning import service_version

logger = logging.getLogger(__name__)

SERVICE_NAME = "npick-ai-worker"

router = APIRouter()

#: 폴링이 꺼진 프로세스의 워밍업 상태. 차가운 것이지 고장난 것이 아니다.
_COLD = WarmupStatus(enabled=False, ready=False, stages=[])

_warmup: WarmupStatus = _COLD

#: 잡 루프 태스크. /health 가 살아 있는지 보고하려면 참조를 들고 있어야 한다.
#: 없으면 폴링이 꺼졌거나 아직 뜨지 않은 것이다.
_job_task: "asyncio.Task[None] | None" = None


def _polling_status() -> JobPollingStatus:
    task = _job_task
    return JobPollingStatus(enabled=task is not None, running=task is not None and not task.done())


@router.get("/health", response_model=HealthResponse, summary="워커 상태·장치·단계 레지스트리")
def health() -> HealthResponse:
    settings = get_settings()
    return HealthResponse(
        status="ok",
        service=SERVICE_NAME,
        version=service_version(),
        device=DeviceStatus(**asdict(detect_device(settings.device))),
        pipeline=PipelineRegistry(
            stage_count=len(STAGES),
            stages=[StageSummary(order=s.order, name=s.name, fatal=s.fatal) for s in STAGES],
        ),
        warmup=_warmup,
        polling=_polling_status(),
    )


def _to_status(report: WarmupReport) -> WarmupStatus:
    return WarmupStatus(
        enabled=True,
        ready=report.ready,
        stages=[
            WarmStageStatus(stage=o.stage, warmed=o.warmed, detail=o.detail) for o in report.stages
        ],
    )


def build_worker(settings: Settings) -> tuple[JobApiClient, JobRunner]:
    """클라이언트와 러너를 함께 만든다.

    **워커 식별자를 여기서 한 번만 정한다.** 따로 만들면 HTTP 헤더와 claim 본문이
    서로 다른 ID 를 말하게 되고, BE 로그에서 워커를 추적할 수 없다.
    """
    worker_id = settings.worker_id or generate_worker_id()
    client = _build_client(settings, worker_id)
    return client, JobRunner.from_settings(settings, client, worker_id=worker_id)


def _build_client(settings: Settings, worker_id: str) -> JobApiClient:
    return JobApiClient(
        base_url=settings.job_api_base_url,
        token=settings.job_api_token.get_secret_value(),
        worker_id=worker_id,
        connect_timeout=settings.job_connect_timeout_seconds,
        read_timeout=settings.job_read_timeout_seconds,
        poll_wait_seconds=settings.job_poll_wait_seconds,
        max_backoff_seconds=settings.job_max_backoff_seconds,
    )


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """폴링이 켜져 있을 때만 잡 루프를 띄운다.

    이 분기가 배포 단위 둘을 가른다 — 잡을 도는 파이프라인 워커와, 폴링하지 않고 동기
    호출만 받는 질의 리졸버. 같은 이미지가 환경 변수 하나로 양쪽이 된다.

    워밍업도 여기서만 돈다. `scenedetect`→`cv2` 임포트가 100MB 를 넘으므로 헬스체크만
    하는 프로세스가 그 비용을 낼 이유가 없다.
    """
    global _warmup, _job_task  # /health 가 읽는 프로세스 단위 상태다
    settings = get_settings()
    if not settings.job_poll_enabled or not settings.job_api_base_url:
        logger.info("잡 폴링 비활성 (NPICK_AI_JOB_POLL_ENABLED / NPICK_AI_JOB_API_BASE_URL)")
        yield
        return

    _warmup = _to_status(await asyncio.to_thread(warm_up))
    client, runner = build_worker(settings)
    task = asyncio.create_task(runner.run(), name="npick-job-runner")
    _job_task = task
    try:
        yield
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        await client.aclose()
        _warmup = _COLD
        _job_task = None


def create_app() -> FastAPI:
    app = FastAPI(
        title="N-Pick AI Worker",
        version=service_version(),
        description="파이프라인 워커. HTTP 표면은 헬스·운영용이고 잡은 BE 에서 받아 온다.",
        lifespan=lifespan,
    )
    app.include_router(router)
    return app
