"""단계 이름을 실행 가능한 핸들러로 잇는다.

`ai/AGENTS.md` 는 단계 구현을 단계 이름과 같은 패키지에 순수 함수로 두고 pipeline run
배선을 하지 않도록 한다. 그래서 `scene_detection/` 은 잡 레이어를 전혀 모르고, 둘을 잇는
어댑터가 여기 하나만 있다.

단계 표의 정본은 `npick_worker.stages` 다. 여기서 이름·순서·치명 여부를 다시 정의하지
않는다. `StageSpec.fatal` 과 `failure_classification` 은 **읽지 않는다** — 치명 여부는
실행이 아니라 run 전체의 정책이라 BE 의 몫이다.
"""

import hashlib
import json
import logging
import platform
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from typing import TYPE_CHECKING, Any, Final

from pydantic import BaseModel, ValidationError

from npick_worker.device import detect_device
from npick_worker.jobs.errors import (
    AsrFailedError,
    ExternalProcessingRefusedError,
    ModelUnavailableError,
    StageUnavailableError,
    TransientStageError,
    UnsupportedMediaError,
    UpstreamOutputInvalidError,
    VlmOutputInvalidError,
)
from npick_worker.jobs.models import ArtifactRef
from npick_worker.jobs.versions import (
    StageRuntime,
    StageVersion,
    output_schema_version,
    stage_version,
)
from npick_worker.settings import get_settings
from npick_worker.versioning import service_version

if TYPE_CHECKING:  # 런타임에 단계 구현을 끌어오지 않는다(ocr 은 onnxruntime 이 딸려 온다).
    from npick_worker.asr.engine import AsrEngine
    from npick_worker.ocr.models import KeyframeRef
    from npick_worker.vlm_metadata.client import VlmClient
    from npick_worker.vlm_metadata.models import SceneKeyframes

logger = logging.getLogger(__name__)

#: keyframe 이미지의 Content-Type. 계약 §4.4 의 PUT 헤더에 그대로 들어간다.
_JPEG_CONTENT_TYPE: Final[str] = "image/jpeg"


@dataclass(frozen=True, slots=True)
class StageContext:
    """단계 하나를 실행하는 데 필요한 전부. 잡 API 타입이 단계로 새지 않게 한다."""

    stage: str
    #: 입력 영상의 로컬 경로. **`StageHandler.needs_video` 가 False 인 단계에서는
    #: None 이다** — 러너가 쓰지 않을 영상을 받지 않는다(`ocr`). 읽는 쪽은
    #: `require_video()` 를 쓴다.
    video_path: Path | None
    storage_key: str
    #: 단계가 파일을 쓸 수 있는 디렉터리. 러너가 잡마다 만들고 잡이 끝나면 지운다.
    #: 단계 구현은 여기 밖에 쓰지 않는다 — 미디어 루트도 최종 저장소도 모른다.
    work_dir: Path
    #: 올릴 수 있는 키 접두(`runs/{runId}/{stage}/a{attempt}/`). 계약 §5.
    output_key_prefix: str
    #: 상류 단계 산출물. BE 가 `inputs.upstream` 으로 되돌려 준 그대로다.
    upstream: Mapping[str, Any] = field(default_factory=dict)
    #: `storage_key` → 로컬 파일. 러너가 `StageHandler.required_inputs` 가 부른 키만
    #: 미리 받아 둔다. 단계가 직접 받지 않는 이유는 업로드와 같다 — 단계는 순수
    #: 함수이고 잡 API 를 모르며(`ai/AGENTS.md`), 내려받는 동안 heartbeat 가 돌아야
    #: 한다(계약 §4.2 — 연장하는 것은 heartbeat 뿐이다).
    upstream_files: Mapping[str, Path] = field(default_factory=dict)
    params: Mapping[str, Any] = field(default_factory=dict)
    #: Integrity-checked upstream JSON, indexed by the original storageKey.
    artifact_documents: Mapping[str, Mapping[str, Any]] = field(default_factory=dict)

    def require_video(self) -> Path:
        """영상을 쓰는 단계가 경로를 꺼내는 자리.

        `needs_video=True` 로 등록한 단계에서는 언제나 값이 있다. 여기서 걸리면
        등록과 구현이 어긋난 것이므로 조용히 넘기지 않는다.
        """
        if self.video_path is None:
            msg = f"이 단계는 needs_video=False 로 등록돼 있다: {self.stage}"
            raise AssertionError(msg)
        return self.video_path


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
    #: `inputs.upstream` 을 받아 **미리 받아 둬야 할 산출물 키**를 돌려준다.
    #: 없으면 이 단계는 배정의 입력 미디어만 쓴다. 러너가 이 목록을 heartbeat 가
    #: 도는 동안 받아 `StageContext.upstream_files` 로 넘긴다.
    required_inputs: Callable[[Mapping[str, Any]], tuple[str, ...]] | None = None
    #: 입력 영상이 필요한가. False 면 러너가 **영상을 해석하지 않는다** —
    #: `transport: "http"` 에서 원본 전체를 내려받는 비용이 그대로 없어진다.
    #: `ocr` 은 상류가 올린 keyframe 만 읽으므로 False 다.
    needs_video: bool = True


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
        result = detect_scenes(ctx.require_video())
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
    detection = upstream.scene_detection
    scenes = tuple(
        SceneSpan(
            scene_index=scene.scene_index,
            start_time_ms=scene.start_time_ms,
            end_time_ms=scene.end_time_ms,
        )
        for scene in detection.scenes
    )

    # 상류가 필수로 보내는 두 값을 실제로 쓴다. 받아 놓고 쓰지 않으면 계약이 요구하는
    # 필드가 검증되지 않은 채 남고, 어긋남이 실패가 아니라 조용히 틀린 timestamp 로 나온다.
    covered_ms = max(scene.end_time_ms for scene in scenes)
    if covered_ms != detection.media_duration_ms:
        # scene_detection 은 마지막 scene 을 영상 끝에서 닫으므로 두 값은 같아야 한다
        # (scene_detection 의 `_to_scenes` 불변식). 다르면 scenes 와 나머지 필드가 서로
        # 다른 산출물에서 온 것이고, 그러면 frameRate 도 이 미디어의 것이 아닐 수 있다.
        msg = (
            f"상류 scene 이 덮는 끝과 보고한 길이가 다르다: "
            f"{covered_ms}ms vs {detection.media_duration_ms}ms"
        )
        raise UpstreamOutputInvalidError(msg)

    try:
        result = extract_keyframes(
            ctx.require_video(),
            scenes,
            ctx.work_dir,
            expected_frame_rate=detection.frame_rate,
        )
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


def _ocr_keyframes(upstream: Mapping[str, Any]) -> tuple["KeyframeRef", ...]:
    """상류 `frameExtraction` 산출물을 읽을 대상 목록으로 옮긴다.

    `required_inputs` 와 `_run_ocr` 이 둘 다 부른다. 한 번은 어떤 파일을 받아야 하는지
    알려고, 한 번은 실제로 읽으려고다. 두 곳에서 따로 파싱하면 받아 온 파일과 읽는
    대상이 어긋날 수 있으므로 조립은 이 함수 하나다.
    """
    from npick_worker.jobs.models import OcrUpstream
    from npick_worker.ocr import KeyframeRef

    upstream_model = _parse_upstream(OcrUpstream, upstream)
    return tuple(
        KeyframeRef(
            scene_index=keyframe.scene_index,
            timestamp_ms=keyframe.timestamp_ms,
            storage_key=keyframe.storage_key,
        )
        for scene in upstream_model.frame_extraction.scenes
        for keyframe in scene.keyframes
    )


def _ocr_required_inputs(upstream: Mapping[str, Any]) -> tuple[str, ...]:
    """읽으려면 있어야 하는 keyframe 이미지들.

    같은 키가 두 번 오지 않게 순서를 지키며 중복을 없앤다 — `UNIQUE(scene_id,
    timestamp_ms)` 가 있으니 정상 입력에서는 없을 일이지만, 있으면 같은 파일을 두 번
    받게 된다.
    """
    seen: dict[str, None] = {}
    for keyframe in _ocr_keyframes(upstream):
        seen.setdefault(keyframe.storage_key, None)
    return tuple(seen)


def _run_ocr(ctx: StageContext) -> StageOutcome:
    # 지연 임포트. rapidocr 는 onnxruntime·cv2 를 끌어온다(scene_detection 과 같은 이유).
    from npick_worker.jobs.models import OcrOutput
    from npick_worker.ocr import OcrModelUnavailableError, OcrReadError, read_keyframes

    keyframes = _ocr_keyframes(ctx.upstream)

    missing = [kf.storage_key for kf in keyframes if kf.storage_key not in ctx.upstream_files]
    if missing:
        # 러너가 `required_inputs` 로 받아 왔어야 하는 파일이다. 일부만 읽고 성공으로
        # 반납하면 "그 프레임에는 글자가 없었다" 는 거짓이 정본에 남는다.
        msg = f"keyframe 이미지를 받지 못했다: {len(missing)}건"
        raise UpstreamOutputInvalidError(msg)

    try:
        result = read_keyframes(keyframes, ctx.upstream_files)
    except OcrModelUnavailableError as exc:
        # 어댑터 경계에서 번역한다. 가중치를 못 받은 것은 이 클립의 문제가 아니므로
        # 다른 파드나 다음 시도에서 성공할 수 있다 — 계약 §9.2 의 일시 오류다.
        raise ModelUnavailableError(str(exc)) from exc
    except OcrReadError as exc:
        # 상류가 올린 JPEG 을 열지 못했다. 같은 파일은 다시 읽어도 안 열린다.
        raise UnsupportedMediaError(str(exc)) from exc

    identity = _ocr_identity(
        config_version=result.config_version,
        engine=result.engine,
        engine_version=result.engine_version,
        tokenizer=result.tokenizer,
        merge_version=result.merge_config.version_id,
    )
    detail = {key: value for key, value in identity.items() if key != "configVersion"}
    output = OcrOutput.from_result(result).model_dump(by_alias=True, mode="json")
    # 관측 배열과 그룹 참조를 한 문서에 보존한다. DB ID로 해석하거나 배열만 정렬하면 안 된다.
    artifact_bytes = json.dumps(
        {
            "outputSchemaVersion": output_schema_version(ctx.stage),
            "identity": identity,
            "output": output,
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    artifact_path = ctx.work_dir / "ocr-result.json"
    artifact_path.write_bytes(artifact_bytes)
    return StageOutcome(
        output=output,
        uploads=(
            PendingUpload(
                ref=ArtifactRef(
                    kind="ocr_result",
                    storage_key=_output_key(ctx.output_key_prefix, "ocr-result.json"),
                    byte_size=len(artifact_bytes),
                    content_hash=hashlib.sha256(artifact_bytes).hexdigest(),
                ),
                local_path=artifact_path,
                content_type="application/json",
            ),
        ),
        versions=StageVersion(
            stage_version=stage_version(ctx.stage, identity),
            output_schema_version=output_schema_version(ctx.stage),
            config_version=result.config_version,
            # 가중치를 쓰는 첫 단계다. 프롬프트는 없으므로 키만 남기고 값을 비운다.
            model_version=f"{result.engine}/{result.engine_version}",
            prompt_version=None,
            detail=detail,
            runtime=_runtime(),
        ),
        metrics={
            "keyframes": len(result.keyframes),
            "observations": result.observation_count,
            # 임계값 미달로 unverified 가 된 수. 이 비율이 튀면 그 클립의 화면 글자
            # 품질이나 임계값을 사람이 한 번 봐야 한다는 신호다.
            "unverifiedObservations": result.unverified_count,
            # 독립 관측을 포함한 scene별 병합 그룹 수. 원본 관측 수는 줄이지 않는다.
            "textGroups": result.text_group_count,
            "minConfidence": result.min_confidence,
        },
    )


def _ocr_identity(
    *,
    config_version: str,
    engine: str,
    engine_version: str,
    tokenizer: str,
    merge_version: str,
) -> dict[str, str]:
    """ocr 의 재현 튜플.

    병합 설정을 포함해 축이 다섯이다. `tokenizer` 가 있는 이유는 `ocr_observation.tokens`
    가 이 단계의 산출물이기 때문이다 — Kiwi 설정이 바뀌면 화면에서 읽은 글자가 같아도
    색인이 달라진다. 검색이 0 건이 되는 종류의 변화라 재현 식별자에 들어가야 한다
    (`docs/architecture/02-container.md:110`).

    **`merge_version` 에 기본값을 두지 않는다.** `read_keyframes(merge_config=...)` 로
    패키지 기본값이 아닌 병합 설정을 쓸 수 있으므로, 폴백이 있으면 인자를 빠뜨린 호출자가
    실제로 돌린 설정 대신 기본값을 `stageVersion` 에 적는다. 그 값으로는 재현이 안 되는데
    재현 식별자가 된 것이므로, 한 번 더 적는 수고보다 거짓 식별자를 막는 쪽을 택한다.
    """
    return {
        "configVersion": config_version,
        "engine": engine,
        "engineVersion": engine_version,
        "tokenizer": tokenizer,
        "mergeConfigVersion": merge_version,
    }


def _warm_ocr() -> str:
    """설정을 미리 읽고 모델을 미리 올린다.

    이 단계는 앞의 둘과 달리 **가중치를 쓴다.** 첫 잡에서 모델을 내려받으면 그 시간이
    통째로 그 잡의 처리 시간이 되고, 내려받기가 실패하면 잡 하나가 그 이유로 죽는다.
    기동 때 하면 `/health` 로 드러난다.

    `shared_engine` 으로 만드는 것이 요점이다. 여기서 만들고 버리면 앞당겨지는 것이
    가중치 내려받기뿐이고, ONNX 세션 생성 비용은 첫 잡이 아니라 **모든 잡이** 낸다.
    캐시된 인스턴스를 잡과 `_declared_version` 이 그대로 받아야 이 docstring 이
    사실이 된다.
    """
    from npick_worker.ocr import get_default_config, shared_engine

    config = get_default_config()
    engine = shared_engine(config)
    return f"config={config.version_id} engine={engine.name} {engine.version}"


def _vlm_scenes(upstream: Mapping[str, Any]) -> tuple["SceneKeyframes", ...]:
    """상류 `frameExtraction` 산출물을 장면별 keyframe 묶음으로 옮긴다.

    `required_inputs` 와 `_run_vlm_metadata` 가 둘 다 부른다. 조립을 한 함수에 두는
    이유는 `_ocr_keyframes` 와 같다 — 두 곳에서 따로 파싱하면 받아 온 파일과 실제로
    모델에 넣는 대상이 어긋날 수 있다.
    """
    from npick_worker.jobs.models import VlmMetadataUpstream
    from npick_worker.vlm_metadata import KeyframeRef as VlmKeyframeRef
    from npick_worker.vlm_metadata import SceneKeyframes as VlmSceneKeyframes
    from npick_worker.vlm_metadata.grounding import OcrRef, OcrText

    parsed = _parse_upstream(VlmMetadataUpstream, upstream)
    observations = parsed.ocr.observations if parsed.ocr is not None else ()
    frames = {
        (scene.scene_index, frame.timestamp_ms, frame.storage_key)
        for scene in parsed.frame_extraction.scenes
        for frame in scene.keyframes
    }
    if any((o.scene_index, o.timestamp_ms, o.storage_key) not in frames for o in observations):
        raise UpstreamOutputInvalidError("OCR 관측이 상류 키프레임을 참조하지 않는다")
    return tuple(
        VlmSceneKeyframes(
            scene_index=scene.scene_index,
            keyframes=tuple(
                VlmKeyframeRef(
                    scene_index=keyframe.scene_index,
                    timestamp_ms=keyframe.timestamp_ms,
                    storage_key=keyframe.storage_key,
                )
                for keyframe in scene.keyframes
            ),
            ocr=tuple(
                OcrText(
                    ref=OcrRef(o.scene_index, o.timestamp_ms, o.storage_key, index),
                    raw_text=o.raw_text,
                    text_key=o.text_key,
                    confidence=o.confidence,
                )
                for index, o in enumerate(observations)
                if o.scene_index == scene.scene_index
            ),
        )
        for scene in parsed.frame_extraction.scenes
    )


def _vlm_selected_keys(upstream: Mapping[str, Any]) -> tuple[str, ...]:
    """모델에 **실제로 넣을** keyframe 의 키. 순서를 지키며 중복을 없앤다.

    상류가 준 전부가 아니라 고른 것만이다. 장면당 상한(`max_keyframes_per_scene`)을
    넘으면 `select_keyframes` 가 골라내므로, 전부 받아 오면 쓰지 않을 이미지를 내려받아
    lease 시간을 쓴다. 외부 어댑터에서는 그 차이가 payload size 판정에도 들어간다.
    """
    from npick_worker.vlm_metadata import get_default_config, select_keyframes

    config = get_default_config()
    seen: dict[str, None] = {}
    for scene in _vlm_scenes(upstream):
        for keyframe in select_keyframes(scene, config):
            seen.setdefault(keyframe.storage_key, None)
    return tuple(seen)


def _run_vlm_metadata(ctx: StageContext) -> StageOutcome:
    # 지연 임포트. transformers·torch 는 헬스체크만 하는 프로세스가 낼 비용이 아니다
    # (ocr 의 onnxruntime 과 같은 이유).
    from npick_worker.jobs.models import VlmMetadataOutput
    from npick_worker.jobs.vlm_inputs import attach_mapped_transcripts
    from npick_worker.vlm_metadata import (
        VlmCallError,
        VlmModelUnavailableError,
        VlmSchemaInvalidError,
        describe_scenes,
        get_default_config,
    )

    config = get_default_config()
    scenes = attach_mapped_transcripts(
        _vlm_scenes(ctx.upstream), ctx.upstream, ctx.artifact_documents
    )
    selected = _vlm_selected_keys(ctx.upstream)

    missing = [key for key in selected if key not in ctx.upstream_files]
    if missing:
        # 러너가 `required_inputs` 로 받아 왔어야 하는 파일이다. 일부만 보고 성공으로
        # 반납하면 "그 장면은 이렇게 보였다" 는 거짓이 정본에 남는다.
        msg = f"keyframe 이미지를 받지 못했다: {len(missing)}건"
        raise UpstreamOutputInvalidError(msg)

    from npick_worker.vlm_metadata import select_keyframes

    # 한 호출은 한 scene이다. 클립 합계 대신 가장 큰 scene 입력을 사전 검사한다.
    # 외부 어댑터 구현 시에는 직렬화된 실제 요청 크기도 전송 직전에 검사해야 한다.
    max_scene_bytes = max(
        (
            sum(
                ctx.upstream_files[k.storage_key].stat().st_size
                for k in select_keyframes(s, config)
            )
            for s in scenes
        ),
        default=0,
    )
    client = _vlm_client(max_scene_bytes)

    try:
        result = describe_scenes(scenes, ctx.upstream_files, client, config)
    except VlmModelUnavailableError as exc:
        # 가중치를 못 받은 것은 이 클립의 문제가 아니다 — 다른 파드나 다음 시도에서
        # 성공할 수 있다(계약 §9.2 의 일시 오류).
        raise ModelUnavailableError(str(exc)) from exc
    except VlmSchemaInvalidError as exc:
        # 형식·어휘·근거가 어긋났다. temperature 0 이므로 다시 물어도 같은 답이 온다.
        raise VlmOutputInvalidError(str(exc)) from exc
    except VlmCallError as exc:
        # 호출 자체의 실패(타임아웃·런타임 오류)다. 출력 내용의 문제와 갈라야 한다.
        raise TransientStageError(str(exc)) from exc

    identity = _vlm_identity(
        config_version=result.config_version,
        engine=result.engine,
        engine_version=result.engine_version,
        model_version=result.model_version,
        tokenizer=result.tokenizer,
    )
    detail = {key: value for key, value in identity.items() if key != "configVersion"}
    return StageOutcome(
        output=VlmMetadataOutput.from_result(result).model_dump(by_alias=True, mode="json"),
        versions=StageVersion(
            stage_version=stage_version(ctx.stage, identity),
            output_schema_version=output_schema_version(ctx.stage),
            config_version=result.config_version,
            # 가중치와 프롬프트를 **둘 다** 쓰는 첫 단계다. 앞의 세 단계에서 값이 비어
            # 있던 두 키가 여기서 처음 채워진다.
            model_version=result.model_version,
            prompt_version=result.prompt_version,
            detail=detail,
            runtime=_runtime(),
        ),
        metrics={
            "scenes": result.scene_count,
            # 설명이 만들어진 장면 수. `scenes` 보다 작으면 근거가 없어 비운 장면이 있다.
            "captionedScenes": result.caption_count,
            "tagCandidates": result.tag_candidate_count,
            # `unknown` 으로 남은 장면 수. 이 비율이 튀면 프롬프트나 모델을 사람이 봐야
            # 한다는 신호다(`ocr` 의 `unverifiedObservations` 와 같은 용도).
            "unknownShotTypes": result.unknown_shot_type_count,
            # 모델이 **문자열 표기**로 '없음' 을 써서 모은 자리의 수
            # (`vlm_metadata/normalize.py`). 0 이 아니면 모델이 계약과 다른 어휘를 쓰고
            # 있다는 뜻이다 — 값은 정본에 들어가되 그 사실은 여기 남는다.
            "normalizedValues": result.normalized_value_count,
            # 계약의 `null` 을 한 칸 다른 자리에 써서 옮긴 자리의 수. **0 이 아닌 것이
            # 정상이다** — 읽을 것이 없는 장면(암전·전환)마다 오른다. 위 값과 합쳐 세면
            # 그쪽이 신호로 쓸 수 없게 되어 따로 올린다.
            "reshapedValues": result.reshaped_value_count,
            "keyframesSent": len(selected),
        },
    )


def _vlm_client(payload_bytes: int) -> "VlmClient":
    """어느 어댑터로 나갈지 고른다. **외부 전송 판정이 여기 있다.**

    판정을 모듈이 아니라 여기서 하는 이유는 `vlm_metadata/__init__.py` 가 적어 둔
    경계다 — 단계 구현은 어느 provider 로 나가는지 모르고, 고르는 쪽이 PRD §12.4 의
    조건을 책임진다.

    외부 경로는 **지금 항상 거절된다.** clip 별 외부 처리 권리 확인을 실어 보내는 자리가
    잡 계약에 없고(`external_policy.py` 모듈 docstring), deployment 수준 허용이 그것을
    대신할 수 없다. 거절은 `EXTERNAL_PROCESSING_NOT_ALLOWED`(영구)이고 **전송 전**이다.
    """
    from npick_worker.vlm_metadata.external_policy import (
        PAYLOAD_CATEGORY_SELECTED_KEYFRAMES,
        ExternalCallRequest,
        ExternalProcessingNotAllowedError,
        authorize,
    )

    settings = get_settings()
    if settings.vlm_backend == "transformers":
        from npick_worker.vlm_metadata.transformers_backend import shared_client

        return shared_client(settings)

    request = ExternalCallRequest(
        model=settings.vlm_external_model,
        endpoint=settings.vlm_external_endpoint,
        payload_category=PAYLOAD_CATEGORY_SELECTED_KEYFRAMES,
        payload_bytes=payload_bytes,
        # 설정에서 읽지 않는다. 전역 플래그가 clip 별 권리 확인을 대신하는 것이 PRD 가
        # 금지한 것이다(`external_policy.py`).
        clip_rights_confirmed=False,
    )
    try:
        authorize(request, settings)
    except ExternalProcessingNotAllowedError as exc:
        # 원문·secret 을 싣지 않는다. 사유·크기·판정만 남는다(PRD §12.4 감사 기록).
        logger.warning(
            "외부 VLM 전송을 하지 않았다: %s",
            exc.record.as_log_fields(),
            extra={"authorization": exc.record.as_log_fields()},
        )
        raise ExternalProcessingRefusedError(str(exc)) from exc
    # 조건이 전부 맞았더라도 보낼 구현이 없다. 없는 것을 있는 척하지 않는다 —
    # `NO_ADAPTER`(영구 → skipped)가 이 사실의 코드다.
    msg = "외부 VLM 어댑터 구현이 없다"
    raise StageUnavailableError(msg)


def _vlm_identity(
    *,
    config_version: str,
    engine: str,
    engine_version: str,
    model_version: str,
    tokenizer: str,
) -> dict[str, str]:
    """vlm_metadata 의 재현 튜플. 축이 다섯이다.

    앞 단계들보다 둘 많다. `modelVersion` 은 가중치가 바뀌면 같은 프레임에서 다른 문장이
    나오기 때문이고, `tokenizer` 는 `scene.caption_tokens` 가 이 단계의 산출물이기
    때문이다 — Kiwi 설정이 바뀌면 설명이 같아도 색인이 달라진다(`ocr` 과 같은 이유).

    `promptVersion` 은 여기 없다. `configVersion` 이 설정 파일 전체의 해시이고 프롬프트가
    그 파일의 한 절이므로, 프롬프트가 바뀌면 `configVersion` 도 바뀐다. 두 값을 다 넣으면
    해시에 같은 정보가 두 번 들어간다 — `versions.promptVersion` 으로는 그대로 보고한다.
    """
    return {
        "configVersion": config_version,
        "engine": engine,
        "engineVersion": engine_version,
        "modelVersion": model_version,
        "tokenizer": tokenizer,
    }


def _warm_vlm_metadata() -> str:
    """설정을 미리 읽고 가중치를 미리 올린다.

    `ocr` 과 같은 이유다 — 첫 잡에서 모델을 내려받으면 그 시간이 통째로 그 잡의 처리
    시간이 되고, 내려받기가 실패하면 잡 하나가 그 이유로 죽는다. 기동 때 하면
    `/health` 로 드러난다. 이 단계는 앞의 셋과 달리 **VRAM 을 실제로 잡는다.**

    외부 백엔드로 설정돼 있으면 워밍업할 것이 없다. 예외를 던져 `warmed=False` 로
    보고한다 — 없는 것을 있는 척하지 않는다(`warm_up` 이 예외를 잡아 기록한다).
    """
    from npick_worker.vlm_metadata import get_default_config, prompt_version
    from npick_worker.vlm_metadata.transformers_backend import TransformersVlmClient

    config = get_default_config()
    client = _vlm_client(0)
    loaded = (
        client.warm_up()
        if isinstance(client, TransformersVlmClient)
        else f"model={client.model_version}"
    )
    return f"config={config.version_id} prompt={prompt_version(config)} {loaded}"


def _run_asr(ctx: StageContext) -> StageOutcome:
    # 지연 임포트. faster-whisper 는 `gpu` 그룹의 선택 의존성이라 없는 환경에서도
    # 이 모듈이 임포트돼야 한다(`ai/AGENTS.md`).
    from npick_worker.asr import (
        AsrCallError,
        AsrModelUnavailableError,
        get_default_config,
        transcribe_media,
    )
    from npick_worker.jobs.models import AsrOutput, AsrUpstream

    config = get_default_config()
    upstream = _parse_upstream(AsrUpstream, ctx.upstream)
    selection = upstream.transcript
    candidates = () if selection is None else selection.candidate_ranges

    if selection is not None and selection.asr_required is False:
        # **그래도 돌린다.** 배정이 곧 실행 지시이고(BE 가 이 단계를 pending 으로 두고
        # 골랐다), 무엇을 채택할지는 우선순위를 아는 하류가 정한다(계약 §4.5). 다만
        # 판정과 배정이 어긋난 사실은 남긴다 — 조용히 넘기면 나중에 GPU 분이 어디로
        # 갔는지 알 수 없다.
        logger.info(
            "transcript_selection 은 ASR 이 필요 없다고 판정했으나 배정됐다 (reasonCode=%s)",
            selection.reason_code,
        )

    engine = _asr_engine()
    try:
        result = transcribe_media(ctx.require_video(), engine, config)
    except AsrModelUnavailableError as exc:
        # 가중치를 못 받은 것은 이 클립의 문제가 아니다 — 다른 파드나 다음 시도에서
        # 성공할 수 있다(계약 §9.2 의 일시 오류).
        raise ModelUnavailableError(str(exc)) from exc
    except AsrCallError as exc:
        # 실행의 실패다. **빈 결과와 다른 사실이므로** 성공으로 반납하지 않는다 —
        # 발화 미감지 정상 종료로 바꾸면 티켓이 금지한 일이 된다. 계약이 이 자리에 준
        # 코드는 `ASR_FAILED` 이고, 맨 `TransientStageError` 는 "분류를 미룬다" 는 뜻의
        # `STAGE_FAILED` 로 적힌다 — 분류된 실패를 미분류로 적을 이유가 없다.
        raise AsrFailedError(str(exc)) from exc
    # 오디오를 디코드할 수 없으면 `MediaUnreadableError` 가 그대로 올라간다.
    # `classify` 가 `UNSUPPORTED_MEDIA`(영구)로 옮긴다 — 같은 파일은 다시 열어도 같다.

    identity = _asr_identity(
        config_version=result.config_version,
        engine=result.engine,
        engine_version=result.engine_version,
        model_version=result.model_version,
    )
    detail = {key: value for key, value in identity.items() if key != "configVersion"}
    return StageOutcome(
        # `exclude_none=True` 인 이유는 `reasonCode` 다. 발화를 찾은 실행에 `null` 을
        # 실어 보내면 BE 의 사유 allowlist 에 없는 값이 되고, "빈 segments 만으로 사유를
        # 만들지 않는다" 는 구분이 와이어에서 흐려진다.
        output=AsrOutput.from_result(result).model_dump(
            by_alias=True, mode="json", exclude_none=True
        ),
        versions=StageVersion(
            stage_version=stage_version(ctx.stage, identity),
            output_schema_version=output_schema_version(ctx.stage),
            config_version=result.config_version,
            model_version=result.model_version,
            # 이 단계는 프롬프트를 쓰지 않는다. 키는 남기고 값만 비운다.
            prompt_version=None,
            detail=detail,
            runtime=_runtime(),
        ),
        metrics={
            "segments": len(result.segments),
            # 엔진이 낸 수. `segments` 보다 크면 계약을 못 지켜 버린 구간이 있다.
            "rawSegments": result.raw_segment_count,
            "droppedBlank": result.dropped_blank,
            "droppedDegenerate": result.dropped_degenerate,
            # 내보낸 구간의 총 길이. 무음 표본에서 이 값이 크면 환각을 의심한다.
            "speechMs": result.speech_ms,
            "vadEnabled": result.vad_enabled,
            # VAD 가 발화로 남긴 오디오 길이. `null` 이면 VAD 를 끄고 돌아 판정이 없다.
            # **이 값이 크고 `speechMs` 가 0 인 실행이 "무음" 이 아니다** — 말은 있었는데
            # 임계에 걸려 문장이 안 나온 것이고, 그 구분이 여기 남아야 나중에 보인다.
            "vadSpeechMs": result.vad_speech_ms,
            # 상류 판정. 없으면 `null` 이고, 그것도 사실이다 — 선택 단계 없이 돈 run 이다.
            "asrRequired": None if selection is None else selection.asr_required,
            "candidateRanges": len(candidates),
        },
    )


def _asr_engine() -> "AsrEngine":
    """엔진을 가져온다. **실패의 종류를 여기서 가른다.**

    라이브러리가 없는 것과 가중치가 없는 것은 다른 사실이다. 앞엣것은 이 이미지의
    성질이라 재시도가 고칠 수 없고(`NO_ADAPTER`, 영구 → `skipped`), 뒤엣것은 캐시
    볼륨·내려받기의 문제라 다른 파드에서 성공할 수 있다(`MODEL_UNAVAILABLE`, 일시).
    한 코드로 합치면 "이 워커에 ASR 이 없다" 와 "지금 없다" 를 구분할 수 없다.
    """
    from npick_worker.asr import AsrModelUnavailableError
    from npick_worker.asr.faster_whisper_backend import AsrRuntimeMissingError, shared_engine

    try:
        return shared_engine()
    except AsrRuntimeMissingError as exc:
        raise StageUnavailableError(str(exc)) from exc
    except AsrModelUnavailableError as exc:
        raise ModelUnavailableError(str(exc)) from exc


def _asr_identity(
    *, config_version: str, engine: str, engine_version: str, model_version: str
) -> dict[str, str]:
    """asr 의 재현 튜플. 축이 넷이다.

    `modelVersion` 이 있는 이유는 `vlm_metadata` 와 같다 — 가중치가 바뀌면 같은 오디오에서
    다른 문장이 나온다. 여기서는 compute type 까지 그 값에 들어간다(`float16` 과 `int8` 은
    같은 모델의 다른 수치다).

    `tokenizer` 축이 **없다.** 이 단계는 색인 토큰을 만들지 않는다 — 대사의 토큰화는
    채택된 구간을 다루는 하류의 일이고, 여기 넣으면 이 단계와 무관한 변경으로
    `stageVersion` 이 바뀌어 재처리가 도는 자리가 생긴다(`ocr` 과 반대 방향의 판단).
    """
    return {
        "configVersion": config_version,
        "engine": engine,
        "engineVersion": engine_version,
        "modelVersion": model_version,
    }


def _warm_asr() -> str:
    """설정을 미리 읽고 가중치를 미리 올린다.

    `ocr`·`vlm_metadata` 와 같은 이유다 — 첫 잡에서 수 GB 를 내려받으면 그 시간이 통째로
    그 잡의 처리 시간이 되고, 내려받기가 실패하면 잡 하나가 그 이유로 죽는다. 기동 때
    하면 `/health` 로 드러난다. 이 단계도 VRAM 을 실제로 잡는다.
    """
    from npick_worker.asr import get_default_config
    from npick_worker.asr.faster_whisper_backend import shared_engine

    config = get_default_config()
    engine = shared_engine()
    # **여기가 가중치를 올리는 유일한 자리다.** 생성자는 라이브러리·모델 설정만 보고
    # 끝나므로(`faster_whisper_backend`), 이 호출을 빼면 로딩이 첫 잡으로 미뤄진다.
    return f"config={config.version_id} {engine.warm_up()}"


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
            StageHandler(
                "vlm_metadata",
                _run_vlm_metadata,
                _warm_vlm_metadata,
                required_inputs=_vlm_selected_keys,
                # 이 단계도 영상을 열지 않는다. 상류가 올린 keyframe JPEG 만 본다.
                needs_video=False,
            ),
            StageHandler(
                "ocr",
                _run_ocr,
                _warm_ocr,
                required_inputs=_ocr_required_inputs,
                # 이 단계는 영상을 열지 않는다. 상류가 올린 keyframe JPEG 만 읽는다.
                needs_video=False,
            ),
            # `required_inputs` 가 없다. 상류 자막 스냅샷 파일을 읽지 않고 판정
            # (`asrRequired`·`candidateRanges`)만 보기 때문이다 — 그 값은 `upstream` 에
            # 인라인으로 오고, 무엇을 채택할지 정하는 일은 하류의 몫이다(계약 §4.5).
            # `needs_video` 는 기본값 True 다. 오디오가 원본 파일 안에 있다.
            StageHandler("asr", _run_asr, _warm_asr),
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
    if stage == "ocr":
        from npick_worker import korean_tokens
        from npick_worker.ocr import get_default_config as get_ocr_config
        from npick_worker.ocr import shared_engine
        from npick_worker.ocr.merge import get_merge_config

        ocr_config = get_ocr_config()
        # **엔진을 만들어 본다.** 여기서 읽는 값(`name` 은 상수, `version` 은
        # importlib.metadata)은 인스턴스와 무관하지만, 가중치를 준비하지 못한 워커가
        # 여기서 걸려 `capability_versions` 가 ocr 를 목록에서 빼야 한다 — 배정받지
        # 못하는 편이 배정받아 매번 죽는 것보다 낫다.
        #
        # **반드시 `shared_engine` 이어야 한다.** 이 함수는 claim long-poll 한 바퀴마다
        # (`runner._claim_request`), 그리고 실패마다(`runner._failure_versions`) 불린다.
        # 매번 새로 만들면 ONNX 세션 생성 비용을 그 주기로 내고, 동기 호출이라 그동안
        # 이벤트 루프가 멈춘다(실패 경로에서는 lease 를 든 채 heartbeat 가 못 뛴다).
        # 캐시는 성공만 담으므로 위의 "실패하면 목록에서 뺀다" 는 그대로 산다.
        ocr_engine = shared_engine(ocr_config)
        return stage_version(
            stage,
            _ocr_identity(
                config_version=ocr_config.version_id,
                engine=ocr_engine.name,
                engine_version=ocr_engine.version,
                tokenizer=korean_tokens.tokenizer_version(),
                # 배정 전에 광고하는 값이라 잡별 병합 설정이 아직 없다. 워커가 잡을
                # 받으면 `read_keyframes` 가 같은 기본값을 쓰므로 여기서 선언한 것과
                # 실제 실행이 일치한다.
                merge_version=get_merge_config().version_id,
            ),
        )
    if stage == "vlm_metadata":
        from npick_worker import korean_tokens
        from npick_worker.vlm_metadata import get_default_config as get_vlm_config

        vlm_config = get_vlm_config()
        # **클라이언트를 만들어 본다.** `ocr` 과 같은 이유다 — 모델이 설정되지 않았거나
        # 런타임이 없는 워커는 여기서 걸려 `capability_versions` 가 이 단계를 목록에서
        # 빼야 한다. 배정받지 못하는 편이 배정받아 매번 `MODEL_UNAVAILABLE` 로 죽는
        # 것보다 낫다. 외부 백엔드로 설정된 워커도 여기서 걸린다(전송 조건 미충족).
        #
        # 미로딩 상태의 main을 광고하면 첫 성공 뒤 SHA로 버전이 바뀐다.
        # 워밍업 성공 전에는 capability에서 제외한다. 폴링/실패 기록 중 로딩하지 않는다.
        # 워밍업 실패 복구는 재워밍업 또는 워커 재시작으로 수행한다.
        vlm_client = _vlm_client(0)
        from npick_worker.vlm_metadata.transformers_backend import TransformersVlmClient

        if isinstance(vlm_client, TransformersVlmClient) and not vlm_client.is_loaded:
            raise ModelUnavailableError("VLM 워밍업이 완료되지 않아 버전을 선언할 수 없다")
        return stage_version(
            stage,
            _vlm_identity(
                config_version=vlm_config.version_id,
                engine=vlm_client.name,
                engine_version=vlm_client.version,
                model_version=vlm_client.model_version,
                tokenizer=korean_tokens.tokenizer_version(),
            ),
        )
    if stage == "asr":
        from npick_worker.asr import get_default_config as get_asr_config

        asr_config = get_asr_config()
        # **엔진을 만들어 본다.** `ocr` 과 같은 이유다 — 라이브러리가 없거나 모델을
        # 고르지 않은 워커는 여기서 걸려 `capability_versions` 가 이 단계를 목록에서
        # 빼야 한다. 배정받지 못하는 편이 배정받아 매번 죽는 것보다 낫다. `gpu` 그룹을
        # 설치하지 않은 개발 환경이 정확히 이 경로로 빠진다. 생성자는 그 둘만 보므로
        # 싸다 — 수 GB 가중치는 `warm_up()` 이 올린다.
        #
        # **가중치가 올라오기 전에는 선언하지 않는다**(`vlm_metadata` 와 같은 가드). 이
        # 함수는 claim long-poll 한 바퀴마다, 그리고 실패마다 **동기로** 불린다. 여기서
        # 로딩을 트리거하면 수 GB 내려받기가 이벤트 루프를 통째로 멈추고, 실패 기록
        # 경로에서는 lease 를 든 채 heartbeat 가 못 뛰어 lease 만료 → 재배정이 된다.
        # 워밍업이 한 번 실패한 워커가 폴링마다 내려받기를 재시도하는 자리도 여기다.
        # 워밍업 실패 복구는 재워밍업 또는 워커 재시작으로 한다.
        asr_engine = _asr_engine()
        from npick_worker.asr.faster_whisper_backend import FasterWhisperEngine

        if isinstance(asr_engine, FasterWhisperEngine) and not asr_engine.is_loaded:
            raise ModelUnavailableError("ASR 워밍업이 완료되지 않아 버전을 선언할 수 없다")
        return stage_version(
            stage,
            _asr_identity(
                config_version=asr_config.version_id,
                engine=asr_engine.name,
                engine_version=asr_engine.version,
                model_version=asr_engine.model_version,
            ),
        )
    msg = f"버전을 선언할 수 없는 단계다: {stage}"
    raise KeyError(msg)
