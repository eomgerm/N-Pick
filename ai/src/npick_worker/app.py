"""FastAPI 앱.

HTTP 표면은 헬스·운영용이다. 작업 수신 방식(FRD §10.8 outbox claim)과
BE 호출 인터페이스는 S15P21A501-70 에서 합의한다. 그 전에 API 를 추가하지 않는다.
"""

from dataclasses import asdict
from importlib.metadata import PackageNotFoundError, version

from fastapi import APIRouter, FastAPI

from npick_worker.device import detect_device
from npick_worker.schemas import (
    DeviceStatus,
    HealthResponse,
    PipelineRegistry,
    StageSummary,
)
from npick_worker.settings import get_settings
from npick_worker.stages import STAGES

SERVICE_NAME = "npick-ai-worker"

router = APIRouter()


def service_version() -> str:
    try:
        return version("npick-worker")
    except PackageNotFoundError:  # 설치되지 않은 채로 실행된 경우
        return "0.0.0+unknown"


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
    )


def create_app() -> FastAPI:
    app = FastAPI(
        title="N-Pick AI Worker",
        version=service_version(),
        description="FRD §2.1 Pipeline Worker 의 골격. 현재는 헬스체크만 제공한다.",
    )
    app.include_router(router)
    return app
