"""/health 응답 스키마.

FRD FR-API-006 에 따라 원본 절대 경로·secret·환경 변수 덤프를 포함하지 않는다.
"""

from typing import Literal

from pydantic import BaseModel

from npick_worker.settings import DeviceChoice


class DeviceStatus(BaseModel):
    requested: DeviceChoice
    resolved: Literal["cuda", "cpu"]
    torch_available: bool
    torch_version: str | None
    cuda_available: bool
    cuda_version: str | None
    device_name: str | None
    total_memory_mb: int | None


class StageSummary(BaseModel):
    order: int
    name: str
    fatal: bool


class PipelineRegistry(BaseModel):
    stage_count: int
    stages: list[StageSummary]


class WarmStageStatus(BaseModel):
    stage: str
    warmed: bool
    detail: str


class WarmupStatus(BaseModel):
    """모델·설정을 미리 준비했는지. 운영에서 "왜 첫 잡이 느린가" 에 답하는 값이다."""

    enabled: bool
    ready: bool
    stages: list[WarmStageStatus]


class HealthResponse(BaseModel):
    status: Literal["ok"]
    service: str
    version: str
    device: DeviceStatus
    pipeline: PipelineRegistry
    #: 차가운 워커도 status 는 "ok" 다. 여기서 실패를 내면 compose 헬스체크가
    #: 컨테이너를 재시작 루프에 빠뜨린다.
    warmup: WarmupStatus
