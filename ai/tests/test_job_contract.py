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
    """`frame_extraction.v1.toml` 기본 설정의 벡터. 값이 바뀌면 여기서 걸린다."""
    from npick_worker.frame_extraction import get_default_config

    assert get_default_config().version_id == "frame-extract/v1:5b266b10"


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
                "configVersion": "frame-extract/v1:5b266b10",
                "engine": "pyav",
                "engineVersion": "18.1.0+numpy2.5.2",
            },
        )
        == "npick.stage.frame_extraction/v1:595427d7"
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
