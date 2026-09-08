"""단계 이름을 실행 가능한 핸들러로 잇는다.

`ai/AGENTS.md` 는 단계 구현을 단계 이름과 같은 패키지에 순수 함수로 두고 pipeline run
배선을 하지 않도록 한다. 그래서 `scene_detection/` 은 잡 레이어를 전혀 모르고, 둘을 잇는
어댑터가 여기 하나만 있다.

단계 표의 정본은 `npick_worker.stages` 다. 여기서 이름·순서·치명 여부를 다시 정의하지
않는다. `StageSpec.fatal` 과 `failure_classification` 은 **읽지 않는다** — 치명 여부는
실행이 아니라 run 전체의 정책이라 BE 의 몫이다.
"""

import logging
import platform
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from typing import Any, Final

from npick_worker.device import detect_device
from npick_worker.jobs.errors import UnsupportedMediaError
from npick_worker.jobs.models import ArtifactRef
from npick_worker.jobs.versions import (
    StageRuntime,
    StageVersion,
    output_schema_version,
    stage_version,
)
from npick_worker.settings import get_settings
from npick_worker.versioning import service_version

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class StageContext:
    """단계 하나를 실행하는 데 필요한 전부. 잡 API 타입이 단계로 새지 않게 한다."""

    stage: str
    video_path: Path
    storage_key: str
    params: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class StageOutcome:
    output: Mapping[str, Any]
    versions: StageVersion
    artifacts: tuple[ArtifactRef, ...] = ()
    metrics: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class StageHandler:
    #: `npick_worker.stages.STAGES_BY_NAME` 의 키여야 한다.
    name: str
    run: Callable[[StageContext], StageOutcome]
    #: 첫 잡 전에 미리 치를 비용. 없으면 None.
    warm: Callable[[], str] | None = None


@dataclass(frozen=True, slots=True)
class WarmOutcome:
    stage: str
    warmed: bool
    detail: str


@dataclass(frozen=True, slots=True)
class WarmupReport:
    ready: bool
    device: str
    stages: tuple[WarmOutcome, ...]


def _runtime() -> StageRuntime:
    """무엇이 이 결과를 만들었는지. torch 는 있을 때만 적는다."""
    device = detect_device(get_settings().device)
    return StageRuntime(
        worker=service_version(),
        python=platform.python_version(),
        torch=device.torch_version,
        cuda=device.cuda_version,
    )


def _run_scene_detection(ctx: StageContext) -> StageOutcome:
    # 지연 임포트. scenedetect 는 cv2 를 끌어오고 그건 100MB 가 넘는다.
    # 헬스체크만 하는 프로세스가 그 비용을 낼 이유가 없다(device.py 의 torch 와 같은 이유).
    from scenedetect import VideoOpenFailure

    from npick_worker.jobs.models import SceneDetectionOutput
    from npick_worker.scene_detection import detect_scenes

    try:
        result = detect_scenes(ctx.video_path)
    except VideoOpenFailure as exc:
        # 어댑터 경계에서 벤더 예외를 번역한다. 이걸 그냥 통과시키면 잡 레이어가
        # scenedetect 를 알아야 하고(ai/AGENTS.md 가 금지한다), 분류를 못 해
        # "열 수 없는 파일" 이 재시도 가능으로 보고된다. 같은 파일은 다시 열어도
        # 안 열리므로 영구 오류다.
        msg = f"영상을 열 수 없다: {ctx.storage_key}"
        raise UnsupportedMediaError(msg) from exc
    identity = _scene_detection_identity(
        config_version=result.config_version,
        detector=result.detector,
        engine=result.engine,
        engine_version=result.engine_version,
    )
    detail = {key: value for key, value in identity.items() if key != "configVersion"}
    return StageOutcome(
        output=SceneDetectionOutput.from_result(result).model_dump(by_alias=True, mode="json"),
        versions=StageVersion(
            stage_version=stage_version(ctx.stage, identity),
            output_schema_version=output_schema_version(ctx.stage),
            config_version=result.config_version,
            # 이 단계는 가중치도 프롬프트도 쓰지 않는다. 키는 남기고 값만 비운다.
            model_version=None,
            prompt_version=None,
            detail=detail,
            runtime=_runtime(),
        ),
        metrics={"scenes": len(result.scenes)},
    )


def _scene_detection_identity(
    *, config_version: str, detector: str, engine: str, engine_version: str
) -> dict[str, str]:
    """scene detection 의 재현 튜플.

    실행 결과에서 만들 때와 claim 에 실을 값을 미리 선언할 때가 **반드시 같아야** 한다.
    두 곳에서 따로 조립하면 워커가 선언한 버전과 실제로 만든 버전이 조용히 갈라지고,
    그러면 BE 의 배정 필터가 하는 일이 없어진다. 그래서 조립은 이 함수 하나다.

    `config_version` 만으로는 부족하다. 그 값은 설정 파일만 해시하므로 라이브러리가
    바뀌면 값이 그대로인데 경계는 달라질 수 있다.
    """
    return {
        "configVersion": config_version,
        "detector": detector,
        "engine": engine,
        "engineVersion": engine_version,
    }


def _warm_scene_detection() -> str:
    """설정을 미리 읽고 엔진을 미리 임포트한다.

    실제로 하는 일이 셋이다. 깨진 toml 이 잡 도중이 아니라 기동 시 터지게 하고,
    `scenedetect`→`cv2` 임포트 비용을 첫 잡에서 떼어 내고, 엔진 버전을 미리 확보한다.
    이 단계는 ML 가중치를 쓰지 않으므로 미리 잡을 GPU 메모리는 없다 — 없는 것을 있는 척
    하지 않는다.
    """
    from npick_worker.scene_detection import PySceneDetectDetector, get_default_config

    config = get_default_config()
    detector = PySceneDetectDetector()
    return f"config={config.version_id} engine={detector.name} {detector.version}"


HANDLERS: Final[Mapping[str, StageHandler]] = MappingProxyType(
    {
        handler.name: handler
        for handler in (
            StageHandler("scene_detection", _run_scene_detection, _warm_scene_detection),
        )
    }
)


def resolve(stage: str) -> StageHandler | None:
    """구현이 있으면 돌려준다. 나머지 아홉 단계는 None 이고 호출부가 생략으로 보고한다."""
    return HANDLERS.get(stage)


def warm_up(stage_names: Iterable[str] | None = None) -> WarmupReport:
    """첫 잡 전에 치를 비용을 미리 치른다.

    어떤 경우에도 예외를 던지지 않는다 — `device.py` 의 규칙과 같다. 워밍업이 실패한
    워커는 느릴 뿐이고, 기동을 막을 이유가 되지 않는다.
    """
    names = list(stage_names) if stage_names is not None else list(HANDLERS)
    device = detect_device(get_settings().device)

    outcomes: list[WarmOutcome] = []
    for name in names:
        handler = HANDLERS.get(name)
        if handler is None or handler.warm is None:
            outcomes.append(WarmOutcome(name, warmed=False, detail="핸들러 없음"))
            continue
        try:
            outcomes.append(WarmOutcome(name, warmed=True, detail=handler.warm()))
        except Exception as exc:  # 워밍업 실패가 기동을 막으면 안 된다
            logger.warning("단계 워밍업 실패 (%s): %s", name, exc)
            outcomes.append(WarmOutcome(name, warmed=False, detail=f"실패: {type(exc).__name__}"))

    return WarmupReport(
        ready=all(outcome.warmed for outcome in outcomes),
        device=device.resolved,
        stages=tuple(outcomes),
    )


def capability_versions() -> dict[str, str]:
    """claim 에 실을 `단계 → stage_version` 목록.

    BE 는 이 값으로 배정을 거른다. 워커가 기대와 다른 버전을 들고 있으면 GPU 분을 쓰기
    전에 걸러지는 편이 낫다.
    """
    versions: dict[str, str] = {}
    for name in HANDLERS:
        # `warm is None` 으로 걸러내지 않는다. "워밍업이 없다" 와 "버전을 선언할 수
        # 없다" 는 다른 말이고, 한데 묶으면 그 단계가 capabilities 에서 조용히 빠진다.
        # BE 는 이 목록에 없는 단계를 배정하지 않으므로, resolve() 가 돌릴 수 있어도
        # 영원히 배정되지 않는다. 선언 불가는 아래 _declared_version 이 알려 준다.
        try:
            versions[name] = _declared_version(name)
        except Exception as exc:  # 선언 실패가 폴링을 막으면 안 된다
            logger.warning("단계 버전을 확인하지 못했다 (%s): %s", name, exc)
    return versions


def _declared_version(stage: str) -> str:
    """실행 없이 계산할 수 있는 단계 버전. `_run_*` 이 만드는 값과 같아야 한다."""
    if stage == "scene_detection":
        from npick_worker.scene_detection import PySceneDetectDetector, get_default_config

        config = get_default_config()
        engine = PySceneDetectDetector()
        return stage_version(
            stage,
            _scene_detection_identity(
                config_version=config.version_id,
                detector=config.detector,
                engine=engine.name,
                engine_version=engine.version,
            ),
        )
    msg = f"버전을 선언할 수 없는 단계다: {stage}"
    raise KeyError(msg)
