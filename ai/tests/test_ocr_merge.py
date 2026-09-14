"""OCR frame 병합: 원본 보존, 보수적 그룹화, 와이어 참조 검증."""

from dataclasses import replace
from pathlib import Path

import pytest

from npick_worker.jobs.models import OcrOutput
from npick_worker.jobs.registry import _ocr_identity
from npick_worker.jobs.versions import output_schema_version, stage_version
from npick_worker.ocr.merge import OcrMergeConfig, get_merge_config, merge_observations
from npick_worker.ocr.models import (
    BoundingBox,
    KeyframeObservations,
    KeyframeRef,
    OcrObservation,
    OcrResult,
)


def observation(text: str, time: int, *, scene: int = 0, confidence: float = 0.5) -> OcrObservation:
    return OcrObservation(
        keyframe=KeyframeRef(scene, time, f"s{scene}/kf-{time}.jpg"),
        raw_text=text,
        tokens=("same-token",),
        confidence=confidence,
        box=BoundingBox(((0.0, 0.0), (100.0, 0.0), (100.0, 30.0), (0.0, 30.0))),
        unverified=confidence < 0.7,
        text_key="same-key",
    )


def result_of(items: list[OcrObservation]) -> OcrResult:
    by_frame: dict[KeyframeRef, list[OcrObservation]] = {}
    for item in items:
        by_frame.setdefault(item.keyframe, []).append(item)
    return OcrResult(
        keyframes=tuple(KeyframeObservations(ref, tuple(obs)) for ref, obs in by_frame.items()),
        config_version="ocr/v1:test",
        engine="fixture",
        engine_version="1",
        tokenizer="fixture/v1",
        min_confidence=0.7,
    )


def test_normalized_merge_preserves_all_originals_and_confidence() -> None:
    items = [
        observation("  \uff2c\uff49\uff46\uff45 Style ", 10),
        observation("life style", 20, confidence=0.6),
    ]
    result = result_of(items)
    output = OcrOutput.from_result(result)
    group = output.text_groups[0]
    assert list(group.observation_indices) == [0, 1]
    assert group.representative_index == 1
    assert [obs.raw_text for obs in output.observations] == [item.raw_text for item in items]
    assert [obs.confidence for obs in output.observations] == [0.5, 0.6]
    assert all(obs.unverified for obs in output.observations)
    assert result.observations == tuple(items)
    assert OcrOutput.model_validate_json(output.model_dump_json()).model_dump(mode="json") == (
        output.model_dump(mode="json")
    )


def test_same_text_key_is_not_sufficient_and_scenes_never_mix() -> None:
    items = [
        observation("10명 구조", 10),
        observation("100명 구조", 20),
        observation("10명 구조", 30, scene=1),
    ]
    assert len(merge_observations(items)) == 3


def test_same_frame_ambiguity_stays_separate() -> None:
    items = [observation("강원도", 10), observation("강원도", 10), observation("강원도", 20)]
    assert len(merge_observations(items)) == 3
    assert (
        len(
            merge_observations(
                [
                    observation("강원도", 10),
                    observation("강원도", 20),
                    observation("강원도", 20),
                ]
            )
        )
        == 3
    )


@pytest.mark.parametrize(
    ("left", "right"),
    [
        ("총 10명 구조 완료", "총 100명 구조 완료"),
        ("온도 -10도 관측", "온도 +10도 관측"),
        ("2026-09-14 보도", "2026-09-15 보도"),
        ("성장률 1.5% 기록", "성장률 15% 기록"),
        ("!!!", "???"),
    ],
)
def test_fuzzy_never_discards_numeric_or_punctuation_differences(left: str, right: str) -> None:
    config = get_merge_config().model_copy(update={"similarity_threshold": 0.7})
    assert len(merge_observations([observation(left, 1), observation(right, 2)], config)) == 2


def test_fuzzy_is_opt_in_and_complete_link_prevents_chain_merge() -> None:
    config = get_merge_config().model_copy(update={"similarity_threshold": 0.9})
    items = [
        observation("abcdefghij", 1),
        observation("abcdefghik", 2),
        observation("abcdefghxk", 3),
    ]
    assert len(merge_observations(items)) == 3
    groups = merge_observations(items, config)
    assert [group.observation_indices for group in groups] == [(0, 1), (2,)]


def test_shuffling_input_does_not_change_group_members_or_representative() -> None:
    items = [observation("뉴스", 30), observation("뉴스", 10), observation("다른 문구", 20)]

    def semantic_groups(items: list[OcrObservation]) -> list[tuple[list[int], int]]:
        return [
            (
                [items[index].keyframe.timestamp_ms for index in group.observation_indices],
                items[group.representative_index].keyframe.timestamp_ms,
            )
            for group in merge_observations(items)
        ]

    assert semantic_groups(items) == semantic_groups(list(reversed(items)))


def test_empty_and_single_observations() -> None:
    assert OcrOutput.from_result(result_of([])).text_groups == []
    assert merge_observations([observation("!", 1)])[0].observation_indices == (0,)


@pytest.mark.parametrize(
    "members,representative,scene",
    [
        ([0, 99], 0, 0),
        ([0, -1], 0, 0),
        ([0, 0], 0, 0),
        ([0], 0, 0),
        ([0, 1], 99, 0),
        ([0, 1], 0, 9),
        ([0, 1], 0, 0),
    ],
)
def test_invalid_group_references_are_rejected(
    members: list[int],
    representative: int,
    scene: int,
) -> None:
    output = OcrOutput.from_result(
        result_of(
            [
                observation("뉴스", 1),
                observation("뉴스", 2, confidence=0.9),
            ]
        )
    ).model_dump(by_alias=True)
    output["textGroups"] = [
        {"sceneIndex": scene, "observationIndices": members, "representativeIndex": representative}
    ]
    with pytest.raises(ValueError):
        OcrOutput.model_validate(output)


def test_same_frame_or_cross_scene_wire_group_is_rejected() -> None:
    payload = OcrOutput.from_result(result_of([observation("뉴스", 1), observation("뉴스", 2)]))
    for changed in (
        replace(observation("뉴스", 2), keyframe=KeyframeRef(1, 2, "other.jpg")),
        observation("뉴스", 1),
    ):
        data = payload.model_dump()
        data["observations"][1]["scene_index"] = changed.keyframe.scene_index
        data["observations"][1]["timestamp_ms"] = changed.keyframe.timestamp_ms
        with pytest.raises(ValueError):
            OcrOutput.model_validate(data)


def test_merge_setting_changes_stage_version_and_output_is_v2() -> None:
    config = get_merge_config()
    changed = config.model_copy(update={"similarity_threshold": 0.9})
    kwargs = {
        "config_version": "ocr/v1:test",
        "engine": "fixture",
        "engine_version": "1",
        "tokenizer": "1",
    }
    assert stage_version("ocr", _ocr_identity(**kwargs, merge_version=config.version_id)) != (
        stage_version("ocr", _ocr_identity(**kwargs, merge_version=changed.version_id))
    )
    assert output_schema_version("ocr") == "npick.stage.ocr.output/v2"
    assert output_schema_version("asr") == "npick.stage.asr.output/v1"


def test_default_merge_identity_matches_contract_vector() -> None:
    assert get_merge_config().version_id == "ocr-merge/v1:28d42216"
    assert (
        stage_version(
            "ocr",
            _ocr_identity(
                config_version="ocr/v1:daaf4c83",
                engine="rapidocr",
                engine_version="rapidocr3.9.2+onnxruntime1.29.0",
                tokenizer="query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0",
            ),
        )
        == "npick.stage.ocr/v1:bc75979d"
    )


def test_merge_config_rejects_unknown_or_nonfinite_threshold(tmp_path: Path) -> None:
    for value in (0, 1.1, float("nan"), float("inf")):
        with pytest.raises(ValueError):
            OcrMergeConfig.model_validate({"schema": "ocr-merge/v1", "similarity_threshold": value})
    with pytest.raises(ValueError):
        OcrMergeConfig.model_validate(
            {"schema": "ocr-merge/v1", "similarity_threshold": 1, "typo": True}
        )
