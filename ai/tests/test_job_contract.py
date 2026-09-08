"""결과 봉투와 버전 규약. 정본은 docs/contracts/job-api.md 다.

기대값은 계약 문서에서 전사하고 대상 모듈에서 import 하지 않는다. 상수를 import 하면
"코드가 코드와 같다" 를 확인하게 되고 계약이 바뀐 것을 아무도 못 잡는다
(tests/test_health.py 의 같은 이유).
"""

from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from npick_worker.jobs.models import SceneDetectionOutput, StageError, StageResult
from npick_worker.jobs.versions import StageVersion, pipeline_version, stage_version
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


# ── scene_detection payload ──────────────────────────────────────────


def test_scene_output_separates_media_duration_from_processing_time() -> None:
    # SceneDetectionResult.duration_ms 는 클립 길이다. 봉투의 duration_ms 는 처리 시간이다.
    output = SceneDetectionOutput(scenes=[], media_duration_ms=76067, frame_rate=30.0)
    dumped = output.model_dump(by_alias=True, mode="json")
    assert dumped["mediaDurationMs"] == 76067
    assert "durationMs" not in dumped
