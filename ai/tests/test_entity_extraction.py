"""FRD F-03/F-04 boundaries: provenance, atomic rejection and original display values."""

# Fullwidth characters are intentional normalization regression fixtures.
# ruff: noqa: RUF001

import json
import math
import re
from pathlib import Path

import pytest
from pydantic import ValidationError

from npick_worker.entity_extraction.config import load_config
from npick_worker.entity_extraction.extractor import (
    EntitySchemaInvalidError,
    EntitySpan,
    SceneInput,
    TextInput,
    extract,
    match_key,
)
from npick_worker.entity_extraction.schema import Candidate, OcrEvidence, Output, TranscriptEvidence


def text(value: str = "서울역에서 만났다", index: int = 0) -> TextInput:
    return TextInput(
        label="ocr_1",
        text=value,
        evidence=OcrEvidence(
            sourceRefType="ocr_observation",
            sceneIndex=index,
            timestampMs=100,
            storageKey="frames/1.jpg",
            observationIndex=0,
        ),
    )


def test_original_spelling_and_roundtrip() -> None:
    scene = SceneInput(scene_index=0, texts=(text("ＳＫ 서울역"),))
    result = extract(
        (scene,),
        {(0, "ocr_1"): (EntitySpan(label="OGG_ECONOMY", start=0, end=2, confidence=0.987654),)},
        label_map=load_config().label_map,
        minimum_confidence=0.0,
    )
    tag = result.scenes[0].tagCandidates[0]
    assert (tag.value, tag.source, tag.confidence) == ("ＳＫ", "rule", 0.9877)
    assert Output.model_validate_json(result.model_dump_json()) == result
    assert "verification" not in result.model_dump_json()


def test_invalid_later_span_rejects_whole_result() -> None:
    good = EntitySpan(label="PS_NAME", start=0, end=2, confidence=0.8)
    bad = EntitySpan(label="PS_NAME", start=0, end=100, confidence=0.8)
    with pytest.raises(EntitySchemaInvalidError):
        extract(
            (SceneInput(scene_index=0, texts=(text(),)),),
            {(0, "ocr_1"): (good, bad)},
            label_map=load_config().label_map,
            minimum_confidence=0.0,
        )


def test_missing_prediction_is_failure_not_empty_success() -> None:
    with pytest.raises(EntitySchemaInvalidError):
        extract(
            (SceneInput(scene_index=0, texts=(text(),)),),
            {},
            label_map=load_config().label_map,
            minimum_confidence=0.0,
        )


@pytest.mark.parametrize("value", [math.nan, math.inf, -0.1, 1.1])
def test_invalid_confidence(value: float) -> None:
    with pytest.raises(ValidationError):
        EntitySpan(label="PS_NAME", start=0, end=1, confidence=value)


def test_mapping_does_not_turn_artifacts_or_pets_into_facilities_or_people() -> None:
    config = load_config()
    assert config.label_map["AF_BUILDING"] == "facility"
    assert config.label_map["AF_ROAD"] == "facility"
    for label in ("AFA_DOCUMENT", "AF_TRANSPORT", "PS_PET", "DT_YEAR", "DT_SEASON"):
        assert config.label_map[label] is None


def test_match_key_keeps_case_and_joiners() -> None:
    assert match_key(" Ｓ\u200bＫ\ufeff\u00ad ") == "SK"
    assert match_key("SK") != match_key("sk")
    assert match_key("a\u200db\u200c") == "a\u200db\u200c"
    assert match_key("A\u200b\u030a") == "Å"


def test_dedup_preserves_independent_sources_and_evidence() -> None:
    first = text("ＳＫ")
    second = TextInput(
        label="tr_1",
        text="SK",
        evidence=TranscriptEvidence(
            sourceRefType="scene",
            sceneIndex=0,
            storageKey="transcript.json",
            segmentId="seg-1",
            s=10,
            e=20,
            sourceDetail="asr",
        ),
    )
    vlm = Candidate(
        type="organization", value="SK", source="vlm", confidence=0.9, evidence=(first.evidence,)
    )
    span = EntitySpan(label="OGG_ECONOMY", start=0, end=2, confidence=0.8)
    result = extract(
        (SceneInput(scene_index=0, texts=(first, second), vlm_candidates=(vlm,)),),
        {(0, "ocr_1"): (span,), (0, "tr_1"): (span,)},
        label_map=load_config().label_map,
        minimum_confidence=0.0,
    )
    tags = result.scenes[0].tagCandidates
    assert len(tags) == 2
    assert tags[1].value == "ＳＫ"
    assert len(tags[1].evidence) == 2


def test_cross_scene_rejected() -> None:
    with pytest.raises(ValueError, match="cross-scene"):
        extract(
            (SceneInput(scene_index=0, texts=(text(index=1),)),),
            {(0, "ocr_1"): ()},
            label_map=load_config().label_map,
            minimum_confidence=0.0,
        )


def test_date_and_extra_fields_rejected() -> None:
    payload = {
        "type": "filmed_date",
        "value": "2026-09-16",
        "source": "rule",
        "confidence": 1.0,
        "evidence": [text().evidence.model_dump()],
    }

    with pytest.raises(ValidationError):
        Candidate.model_validate_json(json.dumps(payload))
    payload.update(type="person", verified=True)
    with pytest.raises(ValidationError):
        Candidate.model_validate_json(json.dumps(payload))


def test_mapping_changes_version() -> None:
    config = load_config()
    changed = config.model_copy(update={"minimum_confidence": 0.5})
    assert config.version != changed.version


def test_contract_example_and_version_prefix() -> None:
    contract = (
        (Path(__file__).resolve().parents[2] / "docs/contracts/job-api.md")
        .read_text(encoding="utf-8")
        .split("### 4.3.6 `entity_extraction`", 1)[1]
        .split("### 4.4", 1)[0]
    )
    example = re.search(r"```json\n(.*?)\n```", contract, re.DOTALL)
    assert example is not None
    parsed = Output.model_validate_json(example.group(1))
    assert parsed.scenes[0].tagCandidates[0].source == "rule"
    assert "npick.stage.entity_extraction.output/v1" in contract
    from npick_worker.jobs.versions import stage_version
    from npick_worker.versioning import version_id

    identity = {
        "algorithmVersion": "entity-extraction/v1",
        "configVersion": "test",
        "modelVersion": "test@revision",
        "engineVersion": "test",
    }
    assert stage_version("entity_extraction", identity) == version_id(
        "npick.stage.entity_extraction/v1", identity
    )


def test_mapping_input_excludes_unselected_transcript() -> None:
    from npick_worker.entity_extraction.inputs import build_inputs
    from npick_worker.scene_transcript_mapping import Scene, Segment, map_transcripts

    mapping = map_transcripts(
        (Scene(0, 0, 100), Scene(1, 100, 200)),
        (
            Segment("cc", 0, 100, "서울역", "uploaded"),
            Segment("asr", 0, 100, "서울역 오인식", "asr"),
            Segment("later", 100, 200, "부산역", "asr"),
        ),
    )
    scenes = build_inputs((0, 1), transcripts=mapping, transcript_storage_key="segments.json")
    assert [t.text for t in scenes[0].texts] == ["서울역"]
    assert [t.text for t in scenes[1].texts] == ["부산역"]
    assert scenes[0].texts[0].evidence.model_dump()["segmentId"] == "cc"


def test_vlm_input_keeps_classification_and_resolved_evidence() -> None:
    from npick_worker.entity_extraction.inputs import build_inputs
    from npick_worker.vlm_metadata.models import (
        KeyframeRef,
        SceneMetadata,
        ShotTypeJudgement,
        TagCandidate,
        VlmResult,
    )

    frame = KeyframeRef(0, 10, "frame.jpg")
    tag = TagCandidate(confidence=0.8, evidence=(frame,), type="scene_type", value="행사")
    vlm = VlmResult(
        scenes=(
            SceneMetadata(0, ShotTypeJudgement(0.5, (frame,), "b_roll"), tag_candidates=(tag,)),
        ),
        schema_version="vlm-metadata/v2",
        config_version="test",
        prompt_version="test",
        engine="fixture",
        engine_version="1",
        model_version="fixture@1",
        tokenizer="test",
    )
    scenes = build_inputs((0,), vlm=vlm)
    output = extract(scenes, {}, label_map=load_config().label_map, minimum_confidence=0.0)
    assert output.scenes[0].tagCandidates[0].source == "vlm"
    assert output.scenes[0].tagCandidates[0].evidence[0].model_dump()["storageKey"] == "frame.jpg"


def test_ocr_merge_uses_representative_original_observation() -> None:
    from npick_worker.entity_extraction.inputs import build_inputs
    from npick_worker.ocr.models import (
        BoundingBox,
        KeyframeObservations,
        KeyframeRef,
        OcrObservation,
        OcrResult,
    )

    observations = tuple(
        OcrObservation(
            keyframe=KeyframeRef(0, i * 100, f"kf-{i}.jpg"),
            raw_text=value,
            tokens=(),
            confidence=confidence,
            box=BoundingBox(((0.0, 0.0), (2.0, 0.0), (2.0, 1.0), (0.0, 1.0))),
            unverified=True,
            text_key="SK",
        )
        for i, (value, confidence) in enumerate((("ＳＫ", 0.4), ("SK", 0.6)))
    )
    ocr = OcrResult(
        keyframes=tuple(KeyframeObservations(o.keyframe, (o,)) for o in observations),
        config_version="fixture",
        engine="fixture",
        engine_version="fixture",
        tokenizer="fixture",
        min_confidence=0.7,
    )
    inputs = build_inputs((0,), ocr=ocr)
    assert len(inputs[0].texts) == 1
    assert inputs[0].texts[0].text == "SK"
    assert inputs[0].texts[0].evidence.model_dump()["observationIndex"] == 1
    assert ocr.observations == observations


@pytest.mark.parametrize("offset", [0.5, True, "0"])
def test_adapter_does_not_coerce_bad_offsets(offset: object) -> None:
    from npick_worker.entity_extraction.local_ner import LocalNer

    ner = LocalNer(load_config())
    ner._pipeline = lambda _: [{"entity_group": "PS_NAME", "start": offset, "end": 1, "score": 0.8}]
    with pytest.raises(EntitySchemaInvalidError):
        ner.predict("김")
