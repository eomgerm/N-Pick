"""잡 수신. **파이프라인 워커 전용이다.**

`ai/` 는 배포 단위 둘을 담는다 — 질의 리졸버(동기 호출 전용·재시도 없음)와 파이프라인
워커(long-poll). 이 패키지는 후자의 것이다. long-poll·lease·heartbeat·멱등성 키·재시도
분류는 리졸버에 해당 사항이 없으므로 리졸버 코드를 여기 넣지 않는다. 계약도 같은 이유로
문서가 둘이다(`docs/contracts/`).

이 패키지 이름은 FRD 단계 표의 단계 이름이 아니다. 단계 구현은 단계 이름과 같은
패키지에 있어야 하고(`ai/AGENTS.md`), `jobs` 는 그 표에 없는 이름이므로 충돌하지 않는다.
"""

from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import StageErrorCode, WorkerError, classify
from npick_worker.jobs.media import MediaResolver
from npick_worker.jobs.models import StageError, StageResult
from npick_worker.jobs.registry import (
    HANDLERS,
    StageContext,
    StageHandler,
    StageOutcome,
    WarmupReport,
    capability_versions,
    declared_stages,
    resolve,
    warm_up,
)
from npick_worker.jobs.runner import JobRunner
from npick_worker.jobs.versions import StageVersion, pipeline_version, stage_version

__all__ = [
    "HANDLERS",
    "JobApiClient",
    "JobRunner",
    "MediaResolver",
    "StageContext",
    "StageError",
    "StageErrorCode",
    "StageHandler",
    "StageOutcome",
    "StageResult",
    "StageVersion",
    "WarmupReport",
    "WorkerError",
    "capability_versions",
    "classify",
    "declared_stages",
    "pipeline_version",
    "resolve",
    "stage_version",
    "warm_up",
]
