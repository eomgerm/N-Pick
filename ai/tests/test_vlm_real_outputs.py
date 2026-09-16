"""실측 실행이 남긴 **모델 원문**을 다시 흘려 본다 (S15P21A501-93).

`test_vlm_metadata.py` 가 넣는 JSON 은 우리가 지어낸 것이다. 그것으로 확인되는 것은
"우리가 상상한 깨진 출력을 거부하는가" 이지 **모델이 실제로 무엇을 내는가** 가 아니다.
정규화(`normalize.py`)는 특히 그렇다 — 모델이 `"없음"` 이라고 쓸 것이라는 전제 위에
서 있는데, 그 전제는 실제 출력을 보기 전까지 추측이다.

그래서 이 파일은 `report.main` 이 남긴 실행 기록(`vlm-metadata.json`)의 `rawOutput` 을
**지금 코드로 다시** 파싱·검증한다. 확인하는 것은 넷이다.

- 통과했던 원문이 지금도 통과하고 **같은 값**이 나오는가
- 기록된 정규화 자리가 지금 코드가 만드는 것과 같은가
- 거부됐던 원문이 지금도 거부되는가 — 정규화를 넓히다 진짜 깨진 출력을 받아들이게
  되는 것이 이 작업의 유일한 실패 방식이고, 그것을 잡는 것이 이 파일이다
- 정규화가 멱등인가

**재생 대상은 지금 계약의 기록뿐이다.** `schemaVersion`·`configVersion` 이 다른 기록은
빼고 그 사유를 skip 사유에 적는다. 계약이 바뀌면 같은 원문의 판정이 달라지는 것이
정상이라서다 — 저장소의 실측 기록(`samples/out/vlm-benchmarks/`)은 셋 다
`vlm-metadata/v1` 이고, `max_tag_candidates_per_scene` 이 줄어든 것만으로 그중 두 장면이
지금 거부된다. 그건 코드의 회귀가 아니다.

**실행 기록은 Git 밖에 둔다.** 원문에는 방송 화면에서 읽은 글자와 장면 설명이 들어간다
(`docs/vlm-sample-compare.md` 의 "결과 원문은 Git 밖에 둔다", `.gitignore` 의
`samples/*`). 기록을 찾지 못하면 재생 테스트는 건너뛴다 — `addopts` 의 `-ra` 가 건너뛴
이유를 매 실행 끝에 찍으므로 조용히 사라지지는 않는다.

    NPICK_AI_VLM_RUN_DIR=<run_dir> uv run pytest tests/test_vlm_real_outputs.py

환경 변수가 없으면 `samples/out/` 아래를 훑는다(`report.py --out` 의 관례적 자리).

**검사 함수는 기록이 없어도 돈다.** 아래 `_check_*` 를 마지막 두 테스트가 합성 기록으로
실행한다. 재생 코드가 깨진 채로 GPU 서버까지 가면, 정작 원문이 생긴 날에 확인할 수 있는
것이 없다.
"""

import json
import os
from collections.abc import Sequence
from pathlib import Path
from typing import Any, Final

import pytest

from npick_worker.vlm_metadata import report
from npick_worker.vlm_metadata.client import LabeledImage
from npick_worker.vlm_metadata.config import (
    CallParams,
    VlmMetadataConfig,
    get_default_config,
    load_config,
)
from npick_worker.vlm_metadata.describer import SceneDescription
from npick_worker.vlm_metadata.models import KeyframeRef
from npick_worker.vlm_metadata.normalize import normalize
from npick_worker.vlm_metadata.schema import SCHEMA_VERSION
from npick_worker.vlm_metadata.validator import VlmSchemaInvalidError, parse_output, validate

#: 실행 기록 디렉터리. `report.py --out` 이나 `benchmark.py --out` 이 만든 자리다.
RUN_DIR_ENV: Final[str] = "NPICK_AI_VLM_RUN_DIR"

#: 환경 변수가 없을 때 훑는 자리. `samples/*` 는 `.gitignore` 에 있다.
_DEFAULT_ROOT: Final[Path] = Path(__file__).resolve().parents[1] / "samples" / "out"

_RECORD_NAME: Final[str] = "vlm-metadata.json"

_HOW_TO_RECORD: Final[str] = (
    "실측 기록이 없다. GPU 서버에서 "
    "`python -m npick_worker.vlm_metadata.report <frames_dir> --out <run_dir> --smoke` "
    f"를 돌린 뒤 그 디렉터리를 {RUN_DIR_ENV} 로 가리킨다 (기본 탐색 자리: {_DEFAULT_ROOT})"
)


def _discover() -> list[Path]:
    """실행 기록 파일들. 없으면 빈 목록이다."""
    override = os.environ.get(RUN_DIR_ENV)
    root = Path(override) if override else _DEFAULT_ROOT
    if not root.is_dir():
        return []
    direct = root / _RECORD_NAME
    return [direct] if direct.is_file() else sorted(root.glob(f"**/{_RECORD_NAME}"))


def _read(path: Path) -> dict[str, Any]:
    payload: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return payload


def _stale_reason(path: Path, record: dict[str, Any]) -> str | None:
    """이 기록이 지금 계약의 것이 아니라면 그 이유. 맞으면 `None`.

    **어긋난 기록으로 회귀를 판정하지 않는다.** schema 나 설정이 바뀌면 같은 원문의 판정이
    달라지는 것이 정상이다 — 실제로 `max_tag_candidates_per_scene` 이 줄어든 것만으로 v1
    실측의 두 장면이 지금 거부된다. 그것은 코드의 회귀가 아니라 계약이 바뀐 것이고, 그걸
    빨간 줄로 만들면 이 파일은 곧 꺼진다.

    그렇다고 조용히 넘기지도 않는다. 쓸 수 있는 기록이 하나도 없으면 아래 skip 사유가 무엇이
    왜 낡았는지까지 적어 `-ra` 로 매 실행 끝에 찍힌다.
    """
    versions = record.get("versions", {})
    if versions.get("schemaVersion") != SCHEMA_VERSION:
        return f"{path}: schema {versions.get('schemaVersion')} 의 기록 (지금은 {SCHEMA_VERSION})"
    config = _config_for(path)
    if versions.get("configVersion") != config.version_id:
        return f"{path}: 설정 {versions.get('configVersion')} 의 기록 (지금은 {config.version_id})"
    return None


def _partition() -> tuple[list[tuple[Path, dict[str, Any]]], list[str]]:
    """(지금 계약의 기록, 낡은 기록의 사유). 수집 시점에 한 번만 디스크를 훑는다."""
    usable: list[tuple[Path, dict[str, Any]]] = []
    stale: list[str] = []
    for path in _discover():
        record = _read(path)
        reason = _stale_reason(path, record)
        if reason is None:
            usable.append((path, record))
        else:
            stale.append(reason)
    return usable, stale


_RECORDS, _STALE = _partition()

#: 기록이 필요한 테스트에만 붙인다. 아래 harness 테스트는 기록 없이도 돈다.
requires_records = pytest.mark.skipif(
    not _RECORDS,
    reason=(
        "지금 계약의 실측 기록이 없다. 낡은 기록: " + "; ".join(_STALE)
        if _STALE
        else _HOW_TO_RECORD
    ),
)


def _records() -> list[tuple[Path, dict[str, Any]]]:
    return _RECORDS


def _config_for(path: Path) -> VlmMetadataConfig:
    """그 실행이 쓴 설정. `benchmark.py` 가 `config.toml` 을 함께 남긴다.

    설정이 바뀌면 어휘와 상한이 바뀌어 같은 원문의 판정이 달라진다. 실행의 설정을 두고
    현재 기본값으로 재생하면 무엇이 회귀인지 구분할 수 없다.
    """
    beside = path.parent / "config.toml"
    return load_config(beside) if beside.is_file() else get_default_config()


def _keyframes(row: dict[str, Any], scene_index: int) -> tuple[KeyframeRef, ...]:
    """그 호출에 **실제로 넣었던** keyframe. 근거 라벨(`kf_1`…)이 이 순서를 가리킨다."""
    return tuple(
        KeyframeRef(
            scene_index=scene_index,
            timestamp_ms=int(frame["timestampMs"]),
            storage_key=str(frame["storageKey"]),
        )
        for frame in row["inputs"]
    )


# ── 검사 ────────────────────────────────────────────────────────────────
# 기록 하나를 받아 검사한다. 실패 메시지에 파일과 장면 번호를 넣는다 — GPU 서버에서 돌린
# 사람이 그것으로 원문을 찾아가야 한다.


def _check_accepted(path: Path, record: dict[str, Any]) -> int:
    """통과했던 원문이 지금도 통과하고 **같은 값**이 나오는가. 검사한 장면 수를 돌려준다.

    통과 여부만 보지 않는다. 정규화는 값을 바꾸는 코드라서, "거부되지 않았다" 로는
    `shot_type` 이 조용히 `unknown` 이 되는 종류의 회귀가 드러나지 않는다.
    """
    config = _config_for(path)
    for row in record["scenes"]:
        scene_index = int(row["sceneIndex"])
        where = f"{path} scene {scene_index}"
        parsed = parse_output(row["rawOutput"])
        metadata = validate(parsed.raw, scene_index, _keyframes(row, scene_index), config)

        assert metadata.shot_type.value == row["shotType"]["value"], where
        assert metadata.shot_type.confidence == row["shotType"]["confidence"], where
        if row["caption"] is None:
            assert metadata.caption is None, where
        else:
            assert metadata.caption is not None, where
            assert metadata.caption.value == row["caption"]["value"], where
            assert metadata.caption.confidence == row["caption"]["confidence"], where
        assert [
            {"type": tag.type, "value": tag.value, "confidence": tag.confidence}
            for tag in metadata.tag_candidates
        ] == row["tagCandidates"], where
    return len(record["scenes"])


def _check_normalizations(path: Path, record: dict[str, Any]) -> None:
    """기록된 정규화 자리가 지금 코드가 만드는 것과 같은가.

    `normalizations` 는 `rawOutput` 의 함수다. 둘이 어긋났다는 것은 정규화 규칙이 바뀌었다는
    뜻이고, 그 변화는 실측 기록에서 보이는 순간 사람이 봐야 한다.
    """
    for row in record["scenes"]:
        if "normalizations" not in row:
            # `normalize.py` 이전에 남은 기록이다. 없는 것과 빈 것은 다르므로 빈 목록으로
            # 읽지 않는다.
            continue
        where = f"{path} scene {row['sceneIndex']}"
        produced = list(parse_output(row["rawOutput"]).normalizations)
        assert produced == row["normalizations"], f"{where}: {produced} != {row['normalizations']}"


def _check_rejections(path: Path, record: dict[str, Any]) -> None:
    """거부됐던 원문이 지금도 거부되는가. **이 작업의 유일한 실패 방식이다.**

    정규화를 한 칸씩 넓히면 어느 지점에서 깨진 출력이 통과하기 시작한다. 그 지점은 우리가
    지어낸 예제가 아니라 모델이 실제로 낸 출력에서 먼저 드러난다.
    """
    config = _config_for(path)
    for row in record["rejected"]:
        if row["kind"] != "schema_invalid" or row["rawOutput"] is None:
            # 호출 실패·중단에는 판정할 원문이 없다.
            continue
        scene_index = int(row["sceneIndex"])
        where = f"{path} scene {scene_index}: 거부됐던 원문이 지금은 통과한다"
        with pytest.raises(VlmSchemaInvalidError):
            parsed = parse_output(row["rawOutput"])
            validate(parsed.raw, scene_index, _keyframes(row, scene_index), config)
            pytest.fail(where)


def _check_normalization_is_a_fixed_point(path: Path, record: dict[str, Any]) -> None:
    """정규화가 멱등인가. 두 번째 통과에서 바뀌는 자리가 있으면 정규화가 아니라 변형이다."""
    for row in record["scenes"]:
        once, _ = normalize(json.loads(row["rawOutput"]))
        twice, changed = normalize(once)
        assert changed == (), f"{path} scene {row['sceneIndex']}: {changed}"
        assert twice == once


# ── 실측 기록 ───────────────────────────────────────────────────────────


@requires_records
def test_recorded_outputs_still_validate_to_the_same_values() -> None:
    checked = sum(_check_accepted(path, record) for path, record in _records())
    assert checked, f"기록에 통과한 장면이 하나도 없다: {[str(path) for path in _RECORDS]}"


@requires_records
def test_recorded_normalizations_still_match() -> None:
    for path, record in _records():
        _check_normalizations(path, record)


@requires_records
def test_recorded_rejections_are_still_rejected() -> None:
    for path, record in _records():
        _check_rejections(path, record)


@requires_records
def test_normalizing_a_recorded_output_twice_changes_nothing() -> None:
    for path, record in _records():
        _check_normalization_is_a_fixed_point(path, record)


# ── 재생 코드 자체 ──────────────────────────────────────────────────────


class _StubClient:
    """`report.to_json` 이 버전 칸에 적는 값만 들고 있다. 모델은 부르지 않는다."""

    name = "stub"
    version = "0"
    model_version = "stub-model@0"
    device = "cpu"

    def describe(
        self,
        images: Sequence[LabeledImage],
        system_prompt: str,
        user_prompt: str,
        params: CallParams,
    ) -> str:
        # 이 파일은 이미 나와 있는 원문을 다시 흘려 보는 곳이다. 여기서 모델을 부를 일이
        # 있다면 그건 재생이 아니라 새 실행이고, 조용히 가짜 출력이 섞이는 길이다.
        raise AssertionError("재생 테스트는 모델을 부르지 않는다")


def _described(raw_output: str, keyframes: tuple[KeyframeRef, ...]) -> SceneDescription:
    parsed = parse_output(raw_output)
    metadata = validate(parsed.raw, keyframes[0].scene_index, keyframes, get_default_config())
    return SceneDescription(
        metadata=metadata,
        raw_output=raw_output,
        inputs=keyframes,
        normalizations=parsed.normalizations,
    )


def test_the_replay_checks_run_against_a_synthesized_record(tmp_path: Path) -> None:
    """기록 파일 없이도 재생 코드가 도는지 확인한다.

    합성 기록으로 품질을 말하지 않는다(`docs/vlm-sample-compare.md`: "가짜 모델 결과를
    품질 점수로 사용하지 않는다"). 여기서 확인하는 것은 **검사 함수가 실제 기록 모양을
    읽어 낼 수 있는가** 뿐이고, 그 모양은 `report.to_json` 이 직접 만들어 준다.
    """
    keyframes = tuple(
        KeyframeRef(scene_index=0, timestamp_ms=stamp, storage_key=f"s0000/kf-{stamp:09d}.jpg")
        for stamp in (1000, 2000)
    )
    # 정규화가 실제로 일어나는 출력이다 — 빈 목록만 비교하고 끝나면 `_check_normalizations`
    # 가 도는지 알 수 없다.
    accepted = json.dumps(
        {
            "caption": {"value": "없음", "confidence": 0.9, "evidence": ["kf_1"]},
            "shot_type": {"value": "anchor", "confidence": 0.88, "evidence": ["kf_1", "kf_2"]},
            "scene_type": {"value": "스튜디오", "confidence": 0.77, "evidence": ["kf_1"]},
            "tag_candidates": None,
        },
        ensure_ascii=False,
    )
    described = _described(accepted, keyframes)
    assert described.normalizations == ("caption", "tag_candidates")

    rejected = report.Rejected(
        scene_index=1,
        kind="schema_invalid",
        reason="shot_type 이 어휘 밖이다",
        inputs=keyframes,
        elapsed_seconds=1.0,
        raw_output=json.dumps({"shot_type": {"value": "아무거나", "confidence": 0.5}}),
    )
    record = report.to_json([(described, 1.0)], [rejected], get_default_config(), _StubClient())
    path = tmp_path / _RECORD_NAME
    path.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
    loaded = _read(path)

    assert _stale_reason(path, loaded) is None
    assert _check_accepted(path, loaded) == 1
    _check_normalizations(path, loaded)
    _check_rejections(path, loaded)
    _check_normalization_is_a_fixed_point(path, loaded)


def test_a_record_from_another_contract_is_not_replayed(tmp_path: Path) -> None:
    """옛 계약의 기록을 회귀 기준으로 쓰지 않는다. 대신 그 사실이 사유로 남는다.

    저장소의 실측 기록(`samples/out/vlm-benchmarks/`)이 지금 이 상태다 — 세 후보 모두
    `vlm-metadata/v1` 이고 현재는 v2 다. 그대로 재생하면 설정이 바뀌어 거부되는 장면이
    코드 회귀처럼 보인다.
    """
    path = tmp_path / _RECORD_NAME
    record = {"versions": {"schemaVersion": "vlm-metadata/v1", "configVersion": "x"}}
    reason = _stale_reason(path, record)
    assert reason is not None
    assert "vlm-metadata/v1" in reason and SCHEMA_VERSION in reason

    config = get_default_config()
    same_schema = {"versions": {"schemaVersion": SCHEMA_VERSION, "configVersion": "옛-설정"}}
    stale_config = _stale_reason(path, same_schema)
    assert stale_config is not None
    assert "옛-설정" in stale_config and config.version_id in stale_config
