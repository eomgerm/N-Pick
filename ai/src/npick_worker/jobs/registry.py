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

from pydantic import BaseModel, ValidationError

from npick_worker.device import detect_device
from npick_worker.jobs.errors import UnsupportedMediaError, UpstreamOutputInvalidError
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

#: keyframe 이미지의 Content-Type. 계약 §4.4 의 PUT 헤더에 그대로 들어간다.
_JPEG_CONTENT_TYPE: Final[str] = "image/jpeg"


@dataclass(frozen=True, slots=True)
class StageContext:
    """단계 하나를 실행하는 데 필요한 전부. 잡 API 타입이 단계로 새지 않게 한다."""

    stage: str
    video_path: Path
    storage_key: str
    #: 단계가 파일을 쓸 수 있는 디렉터리. 러너가 잡마다 만들고 잡이 끝나면 지운다.
    #: 단계 구현은 여기 밖에 쓰지 않는다 — 미디어 루트도 최종 저장소도 모른다.
    work_dir: Path
    #: 올릴 수 있는 키 접두(`runs/{runId}/{stage}/a{attempt}/`). 계약 §5.
    output_key_prefix: str
    #: 상류 단계 산출물. BE 가 `inputs.upstream` 으로 되돌려 준 그대로다.
    upstream: Mapping[str, Any] = field(default_factory=dict)
    params: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class PendingUpload:
    """단계가 만든 파일 하나와 그 파일이 올라갈 자리.

    단계가 직접 올리지 않는 이유가 둘이다. 단계 구현은 순수 함수이고 잡 API 를
    모른다(`ai/AGENTS.md`). 그리고 업로드는 lease 를 연장하지 않으므로(계약 §4.2)
    heartbeat 가 도는 동안 러너가 해야 한다 — 단계 안에서 올리면 그 시간이 heartbeat
    없이 흐른다.
    """

    ref: ArtifactRef
    local_path: Path
    content_type: str


@dataclass(frozen=True, slots=True)
class StageOutcome:
    output: Mapping[str, Any]
    versions: StageVersion
    artifacts: tuple[ArtifactRef, ...] = ()
    metrics: Mapping[str, Any] = field(default_factory=dict)
    #: 러너가 올려야 하는 파일. 올린 뒤 `artifacts` 에 합쳐져 봉투로 나간다.
    uploads: tuple[PendingUpload, ...] = ()


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


def _run_frame_extraction(ctx: StageContext) -> StageOutcome:
    # 지연 임포트. av·numpy 는 헬스체크만 하는 프로세스가 낼 비용이 아니다
    # (scene_detection 의 cv2 와 같은 이유).
    from npick_worker.frame_extraction import SceneSpan, extract_keyframes
    from npick_worker.jobs.models import FrameExtractionOutput, FrameExtractionUpstream

    upstream = _parse_upstream(FrameExtractionUpstream, ctx.upstream)
    scenes = tuple(
        SceneSpan(
            scene_index=scene.scene_index,
            start_time_ms=scene.start_time_ms,
            end_time_ms=scene.end_time_ms,
        )
        for scene in upstream.scene_detection.scenes
    )

    try:
        result = extract_keyframes(ctx.video_path, scenes, ctx.work_dir)
    except _ffmpeg_errors() as exc:
        # 어댑터 경계에서 벤더 예외를 번역한다. 통과시키면 잡 레이어가 `av` 를 알아야
        # 하고(ai/AGENTS.md 가 금지한다) 분류를 못 해 재시도 가능으로 보고된다.
        if isinstance(exc, OSError):
            # PyAV 는 errno 기반 오류를 해당 내장 예외(FileNotFoundError 등)와 함께
            # 상속시킨다. 그건 코덱 문제가 아니라 파일 시스템 문제이므로 코드를 여기서
            # 정하지 않고 `classify` 에 맡긴다 — 없는 파일과 깨진 파일은 다른 사실이다.
            raise
        # 같은 파일은 다시 열어도 안 열리므로 영구 오류다.
        msg = f"영상을 열 수 없다: {ctx.storage_key}"
        raise UnsupportedMediaError(msg) from exc

    storage_keys = {
        (keyframe.scene_index, keyframe.timestamp_ms): _output_key(
            ctx.output_key_prefix, keyframe.file_name
        )
        for scene in result.scenes
        for keyframe in scene.keyframes
    }
    identity = _frame_extraction_identity(
        config_version=result.config_version,
        engine=result.engine,
        engine_version=result.engine_version,
    )
    detail = {key: value for key, value in identity.items() if key != "configVersion"}
    return StageOutcome(
        output=FrameExtractionOutput.from_result(result, storage_keys).model_dump(
            by_alias=True, mode="json"
        ),
        versions=StageVersion(
            stage_version=stage_version(ctx.stage, identity),
            output_schema_version=output_schema_version(ctx.stage),
            config_version=result.config_version,
            # 이 단계도 가중치도 프롬프트도 쓰지 않는다. 키는 남기고 값만 비운다.
            model_version=None,
            prompt_version=None,
            detail=detail,
            runtime=_runtime(),
        ),
        metrics={
            "scenes": len(result.scenes),
            "keyframes": result.keyframe_count,
            # 블랭크 판정에 걸렸는데도 대안이 없어 쓴 장 수. 0 이 아니면 그 클립의
            # 대표 이미지를 사람이 한 번 봐야 한다는 신호다.
            "blankKeyframes": result.blank_count,
            "bytes": sum(
                keyframe.byte_size for scene in result.scenes for keyframe in scene.keyframes
            ),
            "imageWidth": result.image_width,
            "imageHeight": result.image_height,
        },
        uploads=tuple(
            PendingUpload(
                ref=ArtifactRef(
                    # `keyframe.storage_key` 와 같은 어휘를 쓴다. 새 식별자를 만들지
                    # 않는다(계약 §4.4).
                    kind="keyframe",
                    storage_key=storage_keys[(keyframe.scene_index, keyframe.timestamp_ms)],
                    byte_size=keyframe.byte_size,
                    content_hash=keyframe.content_sha256,
                ),
                local_path=ctx.work_dir / keyframe.file_name,
                content_type=_JPEG_CONTENT_TYPE,
            )
            for scene in result.scenes
            for keyframe in scene.keyframes
        ),
    )


def _parse_upstream[T: BaseModel](model: type[T], payload: Mapping[str, Any]) -> T:
    """`inputs.upstream` 을 단계가 기대하는 모양으로 검증한다.

    실패를 영구로 본다. 상류 산출물의 모양이 틀렸다면 다시 시도해도 BE 는 같은 것을
    보낸다. 일시로 신고하면 `maxAttempts` 만큼 GPU 분을 태우고 같은 자리에서 죽는다.
    """
    try:
        return model.model_validate(payload)
    except ValidationError as exc:
        msg = f"상류 산출물이 계약과 다르다: {exc.error_count()}건"
        raise UpstreamOutputInvalidError(msg) from exc


def _output_key(prefix: str, file_name: str) -> str:
    """`outputKeyPrefix` 와 파일명을 잇는다.

    접두에 슬래시가 있는지 없는지로 키가 갈리면 BE 의 접두 검사(`JOB_403_001`)가 통과
    여부만 다르고 이유는 알 수 없는 실패가 된다. 여기서 한 번만 정규화한다.
    """
    return f"{prefix.rstrip('/')}/{file_name}"


def _frame_extraction_identity(
    *, config_version: str, engine: str, engine_version: str
) -> dict[str, str]:
    """frame extraction 의 재현 튜플.

    `scene_detection` 과 같은 이유로 조립 지점을 하나로 둔다 — 실행 결과에서 만들 때와
    claim 에 실을 값을 미리 선언할 때가 갈라지면 BE 의 배정 필터가 하는 일이 없어진다.

    `detector` 에 대응하는 항목이 없다. 이 단계에는 고를 구현이 하나뿐이고, 없는 축을
    만들어 두면 그 축이 항상 같은 값이어서 해시에 아무 정보도 넣지 않는다.
    """
    return {
        "configVersion": config_version,
        "engine": engine,
        "engineVersion": engine_version,
    }


def _ffmpeg_errors() -> tuple[type[Exception], ...]:
    """`av` 가 던지는 예외의 뿌리.

    함수로 감싸는 이유는 임포트 시점을 늦추기 위해서다. 모듈 최상단에서 `av` 를
    끌어오면 지연 임포트로 아낀 비용이 그대로 돌아온다. `except` 절의 식은 예외가
    실제로 났을 때만 평가되므로, 정상 경로에서는 이 함수가 불리지 않는다.
    """
    from av.error import FFmpegError

    return (FFmpegError,)


def _warm_frame_extraction() -> str:
    """설정을 미리 읽고 디코더·점수 계산 의존성을 미리 임포트한다.

    `scene_detection` 의 워밍업과 같은 성격이다. 깨진 toml 이 잡 도중이 아니라 기동
    시 터지게 하고, `av`·`numpy` 임포트 비용을 첫 잡에서 떼어 낸다. 이 단계도 ML
    가중치를 쓰지 않으므로 미리 잡을 GPU 메모리는 없다.
    """
    from npick_worker.frame_extraction import PyAvFrameGrabber, get_default_config

    config = get_default_config()
    grabber = PyAvFrameGrabber()
    return f"config={config.version_id} engine={grabber.name} {grabber.version}"


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
            StageHandler("frame_extraction", _run_frame_extraction, _warm_frame_extraction),
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
    if stage == "frame_extraction":
        # 두 단계의 `get_default_config` 가 이름이 같다. 한 함수 안에서 둘을 지연
        # 임포트하므로 별칭을 준다 — 같은 이름에 다른 타입이 묶이면 타입 검사가 막힌다.
        from npick_worker.frame_extraction import (
            PyAvFrameGrabber,
        )
        from npick_worker.frame_extraction import (
            get_default_config as get_frame_config,
        )

        frame_config = get_frame_config()
        grabber = PyAvFrameGrabber()
        return stage_version(
            stage,
            _frame_extraction_identity(
                config_version=frame_config.version_id,
                engine=grabber.name,
                engine_version=grabber.version,
            ),
        )
    msg = f"버전을 선언할 수 없는 단계다: {stage}"
    raise KeyError(msg)
