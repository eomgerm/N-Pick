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
    #: 이 배포가 맡기로 선언한 단계(`NPICK_AI_JOB_STAGES`). 위 `stages` 는 FRD §5.1 표의
    #: 전사라 배포와 무관한 상수이므로 둘을 갈라 둔다 — CPU 워커와 GPU 파드가 무엇을
    #: 맡았는지는 이 값으로만 구분된다.
    #: **claim 에 실리는 목록과 같지 않을 수 있다.** 여기 있어도 모델 미지정·워밍업
    #: 실패인 단계는 claim 에서 빠지고, 그것은 `warmup` 이 보여 준다.
    declared: list[str]


class WarmStageStatus(BaseModel):
    stage: str
    warmed: bool
    detail: str


class WarmupStatus(BaseModel):
    """모델·설정을 미리 준비했는지. 운영에서 "왜 첫 잡이 느린가" 에 답하는 값이다."""

    enabled: bool
    ready: bool
    stages: list[WarmStageStatus]


class JobPollingStatus(BaseModel):
    """잡을 실제로 받아 가고 있는가.

    `warmup` 만으로는 이것을 알 수 없다. 워밍업은 기동 때 한 번 끝나므로, 폴링 루프가
    죽은 뒤에도 `ready: true` 로 남는다. 그 상태의 워커는 밖에서 보면 멀쩡하다.
    """

    enabled: bool
    #: 루프 태스크가 살아 있는가. false 인데 enabled 가 true 면 조용히 멈춘 것이다.
    running: bool


class HealthResponse(BaseModel):
    status: Literal["ok"]
    service: str
    version: str
    device: DeviceStatus
    pipeline: PipelineRegistry
    #: 차가운 워커도 status 는 "ok" 다. 여기서 실패를 내면 compose 헬스체크가
    #: 컨테이너를 재시작 루프에 빠뜨린다.
    warmup: WarmupStatus
    #: 폴링이 멈춰도 status 는 "ok" 다. 같은 이유다 — 잘못된 토큰 하나로 컨테이너가
    #: 재시작 루프에 빠지는 것은 보이지만 노는 컨테이너보다 나쁘다. 모니터링이
    #: polling.running 을 본다.
    polling: JobPollingStatus
