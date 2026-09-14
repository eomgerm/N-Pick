"""결과 봉투와 버전 규약. 정본은 docs/contracts/job-api.md 다.

기대값은 계약 문서에서 전사하고 대상 모듈에서 import 하지 않는다. 상수를 import 하면
"코드가 코드와 같다" 를 확인하게 되고 계약이 바뀐 것을 아무도 못 잡는다
(tests/test_health.py 의 같은 이유).
"""

from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from npick_worker.jobs.errors import classify
from npick_worker.jobs.models import (
    FrameExtractionOutput,
    FrameExtractionUpstream,
    OcrOutput,
    OcrUpstream,
    SceneDetectionOutput,
    StageError,
    StageResult,
)
from npick_worker.jobs.versions import StageVersion, pipeline_version, stage_version
from npick_worker.media_errors import MediaUnreadableError
from npick_worker.versioning import canonical_json, version_id

#: 계약이 정한 versions 객체의 키. 하나라도 늘거나 줄면 BE 와 어긋난다.
EXPECTED_VERSION_KEYS = {
    "stageVersion",
    "outputSchemaVersion",
    "configVersion",
    "modelVersion",
    "promptVersion",
    "detail",
    "runtime",
}

EXPECTED_ENVELOPE_VERSION = "stage-result/v1"

_START = datetime(2026, 9, 7, 9, 20, 19, tzinfo=UTC)
_END = datetime(2026, 9, 7, 9, 21, 0, tzinfo=UTC)


def _version(**overrides: object) -> StageVersion:
    payload: dict[str, object] = {
        "stage_version": "npick.stage.scene_detection/v1:0badc0de",
        "output_schema_version": "npick.stage.scene_detection.output/v1",
        "config_version": "scene-detect/v1:20dfc0a6",
    }
    payload.update(overrides)
    return StageVersion(**payload)  # type: ignore[arg-type]


def _result(**overrides: object) -> StageResult:
    payload: dict[str, object] = {
        "lease_id": "7d1f4e0c",
        "idempotency_key": "398021847361024:scene_detection:1",
        "stage": "scene_detection",
        "attempt": 1,
        "status": "succeeded",
        "started_at": _START,
        "finished_at": _END,
        "duration_ms": 41230,
        "versions": _version(),
        "output": {"scenes": [], "mediaDurationMs": 1, "frameRate": 30.0},
    }
    payload.update(overrides)
    return StageResult(**payload)  # type: ignore[arg-type]


# ── 봉투 필수 필드 (docs/frd.md:131) ──────────────────────────────────


def test_envelope_version_is_pinned() -> None:
    assert _result().envelope_version == EXPECTED_ENVELOPE_VERSION


def test_envelope_version_rejects_unknown_value() -> None:
    with pytest.raises(ValidationError):
        _result(envelope_version="stage-result/v2")


def test_version_requires_stage_version() -> None:
    with pytest.raises(ValidationError):
        _version(stage_version="")


def test_version_requires_output_schema_version() -> None:
    with pytest.raises(ValidationError):
        _version(output_schema_version="")


def test_result_requires_versions() -> None:
    with pytest.raises(ValidationError):
        StageResult(  # type: ignore[call-arg]
            lease_id="a",
            idempotency_key="b",
            stage="scene_detection",
            attempt=1,
            status="succeeded",
            started_at=_START,
            finished_at=_END,
            duration_ms=0,
            output={"x": 1},
        )


# ── model/prompt 는 키가 있고 값만 null 이다 ─────────────────────────


def test_version_keys_are_exactly_the_contract_keys() -> None:
    assert set(_version().model_dump(by_alias=True)) == EXPECTED_VERSION_KEYS


def test_absent_model_and_prompt_serialize_as_null_not_missing() -> None:
    dumped = _version().model_dump(by_alias=True, mode="json")
    assert dumped["modelVersion"] is None
    assert dumped["promptVersion"] is None


def test_result_keeps_null_version_keys_when_serialized() -> None:
    # exclude_none 을 쓰면 키가 사라진다. 봉투 직렬화가 그러지 않는지 확인한다.
    dumped = _result().model_dump(by_alias=True, mode="json")
    assert set(dumped["versions"]) == EXPECTED_VERSION_KEYS


# ── status 별 정합 ───────────────────────────────────────────────────


def test_succeeded_requires_output() -> None:
    with pytest.raises(ValidationError):
        _result(output=None)


def test_succeeded_rejects_error() -> None:
    with pytest.raises(ValidationError):
        _result(error=StageError(code="OCR_FAILED", retryable=True, message="x"))


@pytest.mark.parametrize("status", ["failed", "skipped"])
def test_non_success_requires_error(status: str) -> None:
    with pytest.raises(ValidationError):
        _result(status=status, output=None)


@pytest.mark.parametrize("status", ["failed", "skipped"])
def test_non_success_with_error_is_valid(status: str) -> None:
    result = _result(
        status=status,
        output=None,
        error=StageError(code="NO_ADAPTER", retryable=False, message="핸들러 없음"),
    )
    assert result.status == status


def test_finished_before_started_is_rejected() -> None:
    with pytest.raises(ValidationError):
        _result(started_at=_END, finished_at=_START)


def test_unknown_error_code_is_rejected() -> None:
    with pytest.raises(ValidationError):
        StageError(code="NOPE", retryable=True, message="x")  # type: ignore[arg-type]


# ── 해싱 규약 (docs/contracts/README.md) ─────────────────────────────


def test_canonical_json_ignores_key_order() -> None:
    assert canonical_json({"b": 1, "a": 2}) == canonical_json({"a": 2, "b": 1})


def test_canonical_json_has_no_whitespace() -> None:
    assert canonical_json({"a": 1, "b": 2}) == '{"a":1,"b":2}'


def test_version_id_shape() -> None:
    value = version_id("thing/v1", {"a": 1})
    schema, _, digest = value.partition(":")
    assert schema == "thing/v1"
    assert len(digest) == 8
    assert digest == digest.lower()


def test_version_id_differs_by_schema() -> None:
    assert version_id("a/v1", {"x": 1}) != version_id("b/v1", {"x": 1})


def test_version_id_differs_by_payload() -> None:
    assert version_id("a/v1", {"x": 1}) != version_id("a/v1", {"x": 2})


# ── stage_version / pipeline_version ─────────────────────────────────


def test_stage_version_prefix_does_not_collide_with_config_version() -> None:
    # 두 값이 로그에 나란히 찍힌다. 접두가 같으면 사람이 반드시 헷갈린다.
    value = stage_version("scene_detection", {"configVersion": "scene-detect/v1:20dfc0a6"})
    assert value.startswith("npick.stage.scene_detection/v1:")
    assert not value.startswith("scene-detect/")


def test_stage_version_changes_when_engine_changes() -> None:
    base = {"configVersion": "scene-detect/v1:20dfc0a6", "engine": "pyscenedetect"}
    assert stage_version("scene_detection", {**base, "engineVersion": "0.7.1"}) != stage_version(
        "scene_detection", {**base, "engineVersion": "0.7.2"}
    )


def test_pipeline_version_fits_the_column() -> None:
    value = pipeline_version({f"stage_{i}": f"npick.stage.s{i}/v1:0badc0de" for i in range(10)})
    assert len(value) <= 128


def test_pipeline_version_is_order_independent() -> None:
    a = pipeline_version({"scene_detection": "x", "ocr": "y"})
    b = pipeline_version({"ocr": "y", "scene_detection": "x"})
    assert a == b


def test_pipeline_version_changes_when_any_stage_changes() -> None:
    a = pipeline_version({"scene_detection": "x", "ocr": "y"})
    b = pipeline_version({"scene_detection": "x", "ocr": "z"})
    assert a != b


def test_stage_version_matches_recorded_vector() -> None:
    """BE 가 재계산해야 하는 값이다. 계약 §7 이 이 파일을 벡터의 짝으로 지목한다.

    입력 픽스처로만 등장하면 scenedetect 를 올리거나 toml 을 손댔을 때 테스트는
    녹색인데 문서의 벡터와 BE 의 Java 대조가 조용히 어긋난다.
    """
    assert (
        stage_version(
            "scene_detection",
            {
                "configVersion": "scene-detect/v1:20dfc0a6",
                "detector": "content",
                "engine": "pyscenedetect",
                "engineVersion": "0.7.1",
            },
        )
        == "npick.stage.scene_detection/v1:3ab4bebe"
    )


def test_default_config_version_matches_recorded_vector() -> None:
    """`scene_detection.v1.toml` 기본 설정의 벡터. 값이 바뀌면 여기서 걸린다."""
    from npick_worker.scene_detection import get_default_config

    assert get_default_config().version_id == "scene-detect/v1:20dfc0a6"


def test_pipeline_version_matches_recorded_vector() -> None:
    """BE 가 이 값을 Java 로 다시 계산한다. 고정 벡터로 양쪽을 대조한다.

    이 값이 바뀌면 계약 문서의 벡터도 함께 고쳐야 한다.
    """
    assert (
        pipeline_version(
            {
                "scene_detection": "npick.stage.scene_detection/v1:aaaaaaaa",
                "ocr": "npick.stage.ocr/v1:bbbbbbbb",
            }
        )
        == "npick-pipeline/v1:64960bae4565"
    )


# ── 오류 어휘 (계약 §4.3.1·§9.2) ───────────────────────────────────────


def test_unreadable_media_is_reported_as_unsupported_media() -> None:
    """영상을 열었지만 쓸 수 없는 것은 미디어 문제다. 계약 §4.3.1 이 그렇게 둔다.

    `ValueError` 로 두면 `classify` 가 `VALIDATION_ERROR` 로 번역하는데, 그건 "상류
    산출물·산출물 키가 잘못됐다" 는 다른 사실이다. 둘 다 영구라 재시도를 태우지는 않지만
    정본에 남는 원인이 달라진다.
    """
    code, retryable = classify(
        MediaUnreadableError("비디오 스트림이 없는 파일이다"), "frame_extraction"
    )

    assert (code, retryable) == ("UNSUPPORTED_MEDIA", False)


def test_unreadable_media_wins_over_the_generic_value_error_branch() -> None:
    """`MediaUnreadableError` 는 `ValueError` 하위다. 분기 순서가 뒤집히면 조용히 묻힌다."""
    assert issubclass(MediaUnreadableError, ValueError)
    assert classify(ValueError("상류 산출물이 계약과 다르다"), "frame_extraction") == (
        "VALIDATION_ERROR",
        False,
    )


def test_both_implemented_stages_report_unreadable_media_the_same_way() -> None:
    """같은 사실이 단계에 따라 다른 코드로 기록되지 않는다.

    두 단계가 각자 프레임레이트를 읽고 각자 실패할 수 있으므로, 번역이 한쪽에만 있으면
    같은 파일이 단계에 따라 UNSUPPORTED_MEDIA 와 VALIDATION_ERROR 로 갈린다.
    """
    failure = MediaUnreadableError("프레임레이트를 읽을 수 없다")

    assert classify(failure, "scene_detection") == classify(failure, "frame_extraction")


# ── scene_detection payload ──────────────────────────────────────────


def test_scene_output_separates_media_duration_from_processing_time() -> None:
    # SceneDetectionResult.duration_ms 는 클립 길이다. 봉투의 duration_ms 는 처리 시간이다.
    output = SceneDetectionOutput(scenes=[], media_duration_ms=76067, frame_rate=30.0)
    dumped = output.model_dump(by_alias=True, mode="json")
    assert dumped["mediaDurationMs"] == 76067
    assert "durationMs" not in dumped


# ── frame_extraction payload ─────────────────────────────────────────


def _keyframe(timestamp_ms: int, scene_index: int = 0) -> dict[str, object]:
    return {
        "sceneIndex": scene_index,
        "timestampMs": timestamp_ms,
        "storageKey": (
            f"runs/398021847361024/frame_extraction/a1/s{scene_index:04d}/kf-{timestamp_ms:09d}.jpg"
        ),
    }


def _scene_keyframes(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "sceneIndex": 0,
        "representativeTimestampMs": 4200,
        "keyframes": [_keyframe(4200), _keyframe(1100), _keyframe(7300)],
    }
    payload.update(overrides)
    return payload


def test_frame_output_accepts_the_contract_example() -> None:
    output = FrameExtractionOutput.model_validate(
        {"scenes": [_scene_keyframes()], "imageWidth": 1920, "imageHeight": 1080}
    )
    dumped = output.model_dump(by_alias=True, mode="json")
    assert dumped["scenes"][0]["representativeTimestampMs"] == 4200
    assert dumped["scenes"][0]["keyframes"][0]["timestampMs"] == 4200
    assert dumped["imageWidth"] == 1920


def test_frame_output_requires_the_representative_to_be_first() -> None:
    """`keyframe` 에 대표 표시 컬럼이 없어 순서가 곧 표시다.

    BE 는 이 순서대로 INSERT 하므로 대표가 그 scene 의 최소 `keyframe_id` 가 된다.
    목록을 정렬해 저장하는 구현 변경이 생기면 여기서 걸린다.
    """
    with pytest.raises(ValidationError, match="대표 이미지"):
        FrameExtractionOutput.model_validate(
            {
                "scenes": [_scene_keyframes(representativeTimestampMs=7300)],
                "imageWidth": 1920,
                "imageHeight": 1080,
            }
        )


def test_frame_output_rejects_duplicate_timestamps() -> None:
    """UNIQUE(scene_id, timestamp_ms) 를 BE 에서 터지기 전에 잡는다."""
    with pytest.raises(ValidationError, match="timestamp_ms"):
        FrameExtractionOutput.model_validate(
            {
                "scenes": [
                    _scene_keyframes(keyframes=[_keyframe(4200), _keyframe(4200)]),
                ],
                "imageWidth": 1920,
                "imageHeight": 1080,
            }
        )


def test_frame_output_rejects_a_scene_without_keyframes() -> None:
    """FRD §3 은 대표 이미지 없이 검색 가능으로 표시하지 않도록 요구한다."""
    with pytest.raises(ValidationError):
        FrameExtractionOutput.model_validate(
            {
                "scenes": [_scene_keyframes(keyframes=[])],
                "imageWidth": 1920,
                "imageHeight": 1080,
            }
        )


def test_frame_output_rejects_keyframes_from_another_scene() -> None:
    with pytest.raises(ValidationError, match="다른 scene"):
        FrameExtractionOutput.model_validate(
            {
                "scenes": [
                    _scene_keyframes(keyframes=[_keyframe(4200), _keyframe(7300, scene_index=1)])
                ],
                "imageWidth": 1920,
                "imageHeight": 1080,
            }
        )


def test_frame_upstream_requires_scene_detection() -> None:
    """상류 산출물이 없으면 이 단계는 할 일을 모른다. 빈 결과를 내지 않는다."""
    with pytest.raises(ValidationError):
        FrameExtractionUpstream.model_validate({})


def test_frame_upstream_tolerates_fields_it_does_not_know() -> None:
    """계약 §3 — 받는 모델은 BE 가 필드를 늘려도 죽지 않아야 한다.

    같은 payload 를 보낼 때는 `extra="forbid"` 다. 방향에 따라 정책이 반대인 곳이다.
    """
    upstream = FrameExtractionUpstream.model_validate(
        {
            "sceneDetection": {
                "scenes": [{"sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 2000, "future": 1}],
                "mediaDurationMs": 2000,
                "frameRate": 30.0,
                "alsoFuture": True,
            },
            "ocr": {"observations": []},
        }
    )
    assert [scene.scene_index for scene in upstream.scene_detection.scenes] == [0]


def test_frame_extraction_config_version_matches_recorded_vector() -> None:
    """`frame_extraction.v2.toml` 기본 설정의 벡터. 값이 바뀌면 여기서 걸린다."""
    from npick_worker.frame_extraction import get_default_config

    assert get_default_config().version_id == "frame-extract/v2:a0684794"


def test_frame_extraction_stage_version_matches_recorded_vector() -> None:
    """재현 튜플은 `{configVersion, engine, engineVersion}` 이다.

    `scene_detection` 의 `detector` 에 대응하는 항목이 없다 — 이 단계에는 고를 구현이
    하나뿐이고, 없는 축을 만들면 그 축이 항상 같은 값이어서 해시에 아무 정보도 넣지
    않는다. 이 값이 바뀌면 계약 문서의 벡터도 함께 고쳐야 한다.
    """
    assert (
        stage_version(
            "frame_extraction",
            {
                "configVersion": "frame-extract/v2:a0684794",
                "engine": "pyav",
                "engineVersion": "18.1.0+numpy2.5.2",
            },
        )
        == "npick.stage.frame_extraction/v1:5fa70a50"
    )


def test_pipeline_version_of_the_two_implemented_stages() -> None:
    """구현된 두 단계만으로 만든 롤업. BE 의 Java 포팅과 대조할 두 번째 벡터다."""
    assert (
        pipeline_version(
            {
                "scene_detection": "npick.stage.scene_detection/v1:aaaaaaaa",
                "frame_extraction": "npick.stage.frame_extraction/v1:cccccccc",
            }
        )
        == "npick-pipeline/v1:32d2389f906a"
    )


# ── ocr (계약 §4.3.2) ───────────────────────────────────────────────


def _upstream_keyframe(timestamp_ms: int, scene_index: int = 0) -> dict[str, object]:
    return {
        "sceneIndex": scene_index,
        "timestampMs": timestamp_ms,
        "storageKey": f"runs/1/frame_extraction/a1/s{scene_index:04d}/kf-{timestamp_ms:09d}.jpg",
    }


def _ocr_upstream(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "scenes": [{"sceneIndex": 0, "keyframes": [_upstream_keyframe(4200)]}],
        "imageWidth": 1920,
        "imageHeight": 1080,
    }
    payload.update(overrides)
    return {"frameExtraction": payload}


def test_ocr_upstream_requires_frame_extraction() -> None:
    """읽을 대상이 없으면 이 단계는 할 일을 모른다.

    빈 결과를 성공으로 반납하면 "이 영상에는 화면 글자가 없다" 는 거짓이 정본에 남는다.
    """
    with pytest.raises(ValidationError):
        OcrUpstream.model_validate({})


def test_ocr_upstream_tolerates_fields_it_does_not_know() -> None:
    upstream = OcrUpstream.model_validate(
        {
            "frameExtraction": {
                "scenes": [
                    {
                        "sceneIndex": 0,
                        "representativeTimestampMs": 4200,
                        "keyframes": [{**_upstream_keyframe(4200), "future": 1}],
                    }
                ],
                "imageWidth": 1920,
                "imageHeight": 1080,
                "alsoFuture": True,
            }
        }
    )
    assert upstream.frame_extraction.scenes[0].keyframes[0].timestamp_ms == 4200


def test_ocr_upstream_does_not_require_the_representative_to_be_first() -> None:
    """대표 규약은 `frame_extraction` 이 보낼 때의 자기 검사다.

    여기서 같은 검사를 다시 하면 BE 가 순서를 바꿔 보낸 경우에 OCR 이
    `VALIDATION_ERROR` 로 죽는다. OCR 은 모든 keyframe 을 읽으므로 대표가 어느
    장인지 알 필요가 없다 — 남의 규약을 이 단계의 실패 사유로 삼지 않는다.
    """
    upstream = OcrUpstream.model_validate(
        _ocr_upstream(
            scenes=[
                {
                    "sceneIndex": 0,
                    "representativeTimestampMs": 7300,
                    "keyframes": [_upstream_keyframe(4200), _upstream_keyframe(7300)],
                }
            ]
        )
    )
    assert len(upstream.frame_extraction.scenes[0].keyframes) == 2


def test_ocr_output_flattens_observations() -> None:
    """`ocr_observation` 이 `keyframe_id` 만 참조한다. scene 으로 묶어 봐야 BE 가 편다."""
    from npick_worker.ocr import KeyframeRef, TextDetection, to_observations
    from npick_worker.ocr.models import OcrResult

    box = ((0.0, 0.0), (10.0, 0.0), (10.0, 4.0), (0.0, 4.0))
    keyframes = tuple(
        to_observations(
            KeyframeRef(
                scene_index=0,
                timestamp_ms=timestamp,
                storage_key=f"runs/1/frame_extraction/a1/s0000/kf-{timestamp:09d}.jpg",
            ),
            [TextDetection(text="강원도", confidence=0.99, points=box)],
            min_confidence=0.7,
        )
        for timestamp in (4200, 7300)
    )
    result = OcrResult(
        keyframes=keyframes,
        config_version="ocr/v1:daaf4c83",
        engine="rapidocr",
        engine_version="test",
        tokenizer="query-norm/v1:test",
        min_confidence=0.7,
    )

    payload = OcrOutput.from_result(result).model_dump(by_alias=True, mode="json")

    assert payload["keyframesRead"] == 2
    assert payload["minConfidence"] == 0.7
    assert len(payload["observations"]) == 2
    observation = payload["observations"][0]
    # keyframe 은 ID 가 아니라 이 쌍으로 가리킨다 — BE 가
    # `UNIQUE(scene_id, timestamp_ms)` 로 행을 찾는다.
    assert observation["sceneIndex"] == 0
    assert observation["timestampMs"] == 4200
    assert observation["rawText"] == "강원도"
    assert observation["unverified"] is False
    assert observation["boundingBox"]["points"] == [
        [0.0, 0.0],
        [10.0, 0.0],
        [10.0, 4.0],
        [0.0, 4.0],
    ]
    assert observation["boundingBox"]["width"] == 10.0


def test_wire_bounding_box_is_the_same_shape_the_column_gets() -> None:
    """와이어 payload 와 `bounding_box_json` 이 갈라지면 안 된다.

    `BoundingBox.to_json()` 이 `ocr_observation.bounding_box_json` 의 모양 정본이고
    `ocr/report.py` 도 그것을 쓴다. `OcrOutput.from_result` 가 같은 모양을 손으로 다시
    조립하면 두 벌이 되고, 언젠가 한쪽만 바뀌어 report 출력과 payload 가 조용히
    달라진다. 여기서 두 벌이 아님을 고정한다.
    """
    from npick_worker.ocr import BoundingBox, KeyframeRef, TextDetection, to_observations
    from npick_worker.ocr.models import OcrResult

    box = BoundingBox(points=((3.0, 1.0), (13.0, 1.0), (13.0, 5.0), (3.0, 5.0)))
    keyframes = (
        to_observations(
            KeyframeRef(
                scene_index=0,
                timestamp_ms=4200,
                storage_key="runs/1/frame_extraction/a1/s0000/kf-000004200.jpg",
            ),
            [TextDetection(text="강원도", confidence=0.99, points=box.points)],
            min_confidence=0.7,
        ),
    )
    result = OcrResult(
        keyframes=keyframes,
        config_version="ocr/v1:daaf4c83",
        engine="rapidocr",
        engine_version="test",
        tokenizer="query-norm/v1:test",
        min_confidence=0.7,
    )

    payload = OcrOutput.from_result(result).model_dump(by_alias=True, mode="json")

    assert payload["observations"][0]["boundingBox"] == box.to_json()


def test_ocr_config_version_matches_recorded_vector() -> None:
    """`ocr.v1.toml` 기본 설정의 벡터. 값이 바뀌면 여기서 걸린다."""
    from npick_worker.ocr import get_default_config

    assert get_default_config().version_id == "ocr/v1:daaf4c83"


def test_ocr_stage_version_matches_recorded_vector() -> None:
    """재현 튜플은 `{configVersion, engine, engineVersion, tokenizer}` 다.

    앞의 두 단계와 달리 축이 넷이다. `tokenizer` 가 있는 이유는
    `ocr_observation.tokens` 가 이 단계의 산출물이기 때문이다 — Kiwi 설정이 바뀌면
    읽은 글자가 같아도 색인이 달라지고, 그건 검색이 0 건이 되는 종류의 변화다
    (`docs/architecture/02-container.md:110`).

    이 값이 바뀌면 계약 문서의 벡터도 함께 고쳐야 한다.
    """
    assert (
        stage_version(
            "ocr",
            {
                "configVersion": "ocr/v1:daaf4c83",
                "engine": "rapidocr",
                "engineVersion": "rapidocr3.9.2+onnxruntime1.29.0",
                "tokenizer": "query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0",
            },
        )
        == "npick.stage.ocr/v1:449d6928"
    )


# ── vlm_metadata (계약 §4.3.3) ──────────────────────────────────────


def _vlm_result(**overrides: object) -> object:
    """검증을 통과한 단계 산출물 하나. 와이어 변환만 보는 테스트의 입력이다."""
    from npick_worker.vlm_metadata.models import (
        Caption,
        KeyframeRef,
        SceneMetadata,
        ShotTypeJudgement,
        TagCandidate,
        VlmResult,
    )

    keyframe = KeyframeRef(
        scene_index=0,
        timestamp_ms=4200,
        storage_key="runs/1/frame_extraction/a1/s0000/kf-000004200.jpg",
    )
    scene = SceneMetadata(
        scene_index=0,
        shot_type=ShotTypeJudgement(value="anchor", confidence=0.8, evidence=(keyframe,)),
        caption=Caption(
            value="앵커가 스튜디오에서 소식을 전한다",
            tokens=("앵커", "스튜디오", "소식", "전하다"),
            confidence=0.9,
            evidence=(keyframe,),
        ),
        tag_candidates=(
            TagCandidate(type="scene_type", value="스튜디오", confidence=0.7, evidence=(keyframe,)),
        ),
    )
    values: dict[str, object] = {
        "scenes": (scene,),
        "schema_version": "vlm-metadata/v1",
        "config_version": "vlm-metadata-config/v1:test",
        "prompt_version": "vlm-metadata-prompt/v1:test",
        "engine": "transformers",
        "engine_version": "test",
        "model_version": "example/vlm@main",
        "tokenizer": "query-norm/v1:test",
    }
    values.update(overrides)
    return VlmResult(**values)  # type: ignore[arg-type]


def test_vlm_upstream_requires_frame_extraction() -> None:
    """상류가 없으면 무엇을 볼지 모른다. 빈 결과를 성공으로 반납하지 않는다."""
    from npick_worker.jobs.models import VlmMetadataUpstream

    with pytest.raises(ValidationError):
        VlmMetadataUpstream.model_validate({})


def test_vlm_output_groups_by_scene() -> None:
    """`ocr` 과 반대다. 저장 자리가 `scene` 행의 컬럼이라 scene 단위로 보낸다."""
    from npick_worker.jobs.models import VlmMetadataOutput

    payload = VlmMetadataOutput.from_result(_vlm_result()).model_dump(  # type: ignore[arg-type]
        by_alias=True, mode="json"
    )

    assert payload["metadataSchemaVersion"] == "vlm-metadata/v1"
    scene = payload["scenes"][0]
    assert scene["sceneIndex"] == 0
    assert scene["shotType"]["value"] == "anchor"
    # `scene.caption_tokens` 에 들어가는 문자열. 공백으로 이어진다(색인이 whitespace 다).
    assert scene["caption"]["tokens"] == "앵커 스튜디오 소식 전하다"
    # 근거는 `(sceneIndex, timestampMs)` 쌍이다. `keyframe_id` 는 BE 가 발급한다.
    assert scene["caption"]["evidence"] == [
        {
            "sceneIndex": 0,
            "timestampMs": 4200,
            "storageKey": "runs/1/frame_extraction/a1/s0000/kf-000004200.jpg",
        }
    ]


def test_vlm_output_allows_a_scene_without_a_caption() -> None:
    """근거가 없으면 비운다. `caption: null` 은 정상 payload 다."""
    from npick_worker.jobs.models import VlmMetadataOutput
    from npick_worker.vlm_metadata.models import SceneMetadata, ShotTypeJudgement

    scene = SceneMetadata(
        scene_index=0,
        shot_type=ShotTypeJudgement(value="unknown", confidence=0.1, evidence=()),
    )
    payload = VlmMetadataOutput.from_result(
        _vlm_result(scenes=(scene,))  # type: ignore[arg-type]
    ).model_dump(by_alias=True, mode="json")

    assert payload["scenes"][0]["caption"] is None
    assert payload["scenes"][0]["tagCandidates"] == []
    # `unknown` 만 근거 없이 올 수 있다. `scene.shot_type` 이 NOT NULL 이라 값은 있어야 한다.
    assert payload["scenes"][0]["shotType"] == {
        "value": "unknown",
        "confidence": 0.1,
        "evidence": [],
    }


def test_vlm_config_version_matches_recorded_vector() -> None:
    """`vlm_metadata.v1.toml` 기본 설정의 벡터. 값이 바뀌면 여기서 걸린다."""
    from npick_worker.vlm_metadata import get_default_config

    assert get_default_config().version_id == "vlm-metadata-config/v1:13d50f07"


def test_vlm_prompt_version_matches_recorded_vector() -> None:
    """**렌더링된** 프롬프트의 벡터다. 어휘를 고치면 템플릿이 그대로여도 바뀐다."""
    from npick_worker.vlm_metadata import get_default_config, prompt_version

    assert prompt_version(get_default_config()) == "vlm-metadata-prompt/v1:78a02dbd"


def test_vlm_stage_version_matches_recorded_vector() -> None:
    """재현 튜플은 `{configVersion, engine, engineVersion, modelVersion, tokenizer}` 다.

    축이 다섯인 첫 단계다. `modelVersion` 은 가중치가 바뀌면 같은 프레임에서 다른 문장이
    나오기 때문이고, `tokenizer` 는 `scene.caption_tokens` 가 이 단계의 산출물이기
    때문이다.

    `engineVersion`·`modelVersion` 은 **예시 값**이다. 실제 값은 설치된 런타임과 설정에서
    오므로 고정 벡터로 쓸 수 없다 — 이 벡터가 고정하는 것은 해시 함수와 키 이름이고,
    그것이 BE 의 Java 포팅이 대조해야 하는 것이다. 값이 바뀌면 계약 문서의 벡터도 함께
    고친다.
    """
    assert (
        stage_version(
            "vlm_metadata",
            {
                "configVersion": "vlm-metadata-config/v1:fcd15e10",
                "engine": "transformers",
                "engineVersion": "transformers5.0.0+torch2.13.0",
                "modelVersion": "example/vlm@main",
                "tokenizer": "query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0",
            },
        )
        == "npick.stage.vlm_metadata/v1:325198af"
    )
