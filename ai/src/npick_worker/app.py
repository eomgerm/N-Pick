"""FastAPI 앱.

헬스·운영 표면과 검색 서비스가 부르는 질의 해석 표면을 제공한다.

질의 해석 엔드포인트는 S15P21A501-45 에서 추가했다. `ai/AGENTS.md` 가 가리키던
S15P21A501-70 은 파이프라인 워커(scene·VLM·OCR·ASR·embedding)의 잡 수신 계약이라
검색 시점 모듈과 무관하다는 것이 확인됐다. 파이프라인 잡 수신 방식은 여전히 -70 에서
정한다.
"""

from dataclasses import asdict
from importlib.metadata import PackageNotFoundError, version

from fastapi import APIRouter, FastAPI, HTTPException, status

from npick_worker.device import detect_device
from npick_worker.query_api import (
    QueryResolveRequest,
    QueryResolveResponse,
    resolve,
)
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


@router.post(
    "/query/resolve",
    response_model=QueryResolveResponse,
    summary="검색어 정규화와 AI 해석",
)
def resolve_query_endpoint(request: QueryResolveRequest) -> QueryResolveResponse:
    """정규화는 항상, 해석은 되는 만큼 돌려준다.

    해석 실패는 200 이고 `error` 에 사유가 담긴다 — 호출부는 그 응답의
    `search_tokens` 로 원 검색어 BM25 를 이어간다 (FRD v3.1 §6.2). 400 은 정규화가
    불가능한 질의(빈 값·기호만·불용어만)일 때만 난다.
    """
    try:
        return resolve(request)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc


def create_app() -> FastAPI:
    app = FastAPI(
        title="N-Pick AI Worker",
        version=service_version(),
        description="파이프라인 워커 골격과 검색 시점 질의 해석 표면.",
    )
    app.include_router(router)
    return app
