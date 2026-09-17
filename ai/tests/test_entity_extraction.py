"""FRD F-03/F-04 boundaries: provenance, atomic rejection and original display values."""

# Fullwidth characters are intentional normalization regression fixtures.
# ruff: noqa: RUF001

import json
import math
import re
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

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
from npick_worker.jobs import entity_extraction as job_adapter
from npick_worker.jobs.registry import StageContext


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


def test_discarded_span_with_empty_value_does_not_fail_the_clip() -> None:
    """버려질 span 이 클립 전체를 영구 실패시키지 않는다.

    KPF 라벨 150종 중 74종이 None 으로 매핑된다. 그 중 하나가 공백·ZWSP 만 걸친 구간을
    내면 이 단계는 그것을 어차피 방출하지 않는다. 방출되는 후보의 빈 비교키는 그대로
    `EntitySchemaInvalidError` 로 막힌다(아래 테스트).
    """
    span = EntitySpan(label="DT_DAY", start=2, end=4, confidence=0.9)
    result = extract(
        (SceneInput(scene_index=0, texts=(text("서울​ 역"),)),),
        {(0, "ocr_1"): (span,)},
        label_map=load_config().label_map,
        minimum_confidence=0.0,
    )
    assert result.scenes[0].tagCandidates == ()


def test_emitted_span_with_empty_value_still_rejects_whole_result() -> None:
    span = EntitySpan(label="PS_NAME", start=2, end=4, confidence=0.9)
    with pytest.raises(EntitySchemaInvalidError):
        extract(
            (SceneInput(scene_index=0, texts=(text("서울​ 역"),)),),
            {(0, "ocr_1"): (span,)},
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


# ── 잡 레이어 배선 (계약 §4.3.6) ──────────────────────────────────────


def _observation(scene: int, raw: str, ms: int = 100, confidence: float = 0.9) -> dict[str, object]:
    return {
        "sceneIndex": scene,
        "timestampMs": ms,
        "storageKey": f"runs/1/frames/s{scene}-{ms}.jpg",
        "rawText": raw,
        "tokens": raw,
        "confidence": confidence,
        "unverified": False,
        "textKey": f"k-{raw}",
        "boundingBox": {
            "points": [[0, 0], [10, 0], [10, 10], [0, 10]],
            "x": 0,
            "y": 0,
            "width": 10,
            "height": 10,
        },
    }


def _upstream(**extra: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "sceneDetection": {
            "scenes": [{"sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 1000}],
            "mediaDurationMs": 1000,
            "frameRate": 30.0,
        }
    }
    payload.update(extra)
    return payload


def _context(tmp_path: Path, upstream: dict[str, object]) -> StageContext:
    return StageContext(
        stage="entity_extraction",
        video_path=None,
        storage_key="clips/1/source.mp4",
        work_dir=tmp_path,
        output_key_prefix="runs/1/entity_extraction/a1/",
        upstream=upstream,
    )


class _FakeNer:
    """Stand in for the GPU adapter. The wiring around it is what these tests check."""

    def __init__(
        self,
        spans: Callable[[str], tuple[EntitySpan, ...]] | None = None,
    ) -> None:
        self.spans = spans if spans is not None else (lambda _text: ())
        self.texts: list[str] = []

    def predict(self, text: str) -> tuple[EntitySpan, ...]:
        self.texts.append(text)
        return self.spans(text)


@pytest.fixture
def fake_ner(monkeypatch: pytest.MonkeyPatch) -> Callable[[_FakeNer], _FakeNer]:
    def install(ner: _FakeNer) -> _FakeNer:
        monkeypatch.setattr(job_adapter, "shared_ner", lambda _config: ner)
        return ner

    return install


def test_stage_reads_merged_ocr_and_keeps_the_upstream_observation_index(
    tmp_path: Path, fake_ner: Callable[[_FakeNer], _FakeNer]
) -> None:
    """The evidence index must address the array BE sent, not a regrouped copy of it."""
    ner = fake_ner(
        _FakeNer(lambda t: (EntitySpan(label="LCP_CITY", start=0, end=len(t), confidence=0.9877),))
    )
    upstream = _upstream(
        ocr={
            "observations": [
                _observation(0, "속보", ms=100),
                # The same text on a later frame is one merge group, and its representative
                # is the higher-confidence row at index 2.
                _observation(0, "서울역", ms=200, confidence=0.8),
                _observation(0, "서울역", ms=300, confidence=0.95),
            ],
            "keyframesRead": 3,
            "minConfidence": 0.5,
        }
    )
    outcome = job_adapter.run(_context(tmp_path, upstream))

    assert ner.texts == ["속보", "서울역"]
    candidates = outcome.output["scenes"][0]["tagCandidates"]
    assert [c["value"] for c in candidates] == ["속보", "서울역"]
    assert {c["source"] for c in candidates} == {"rule"}
    assert candidates[1]["evidence"] == [
        {
            "sourceRefType": "ocr_observation",
            "sceneIndex": 0,
            "timestampMs": 300,
            "storageKey": "runs/1/frames/s0-300.jpg",
            "observationIndex": 2,
        }
    ]
    assert outcome.metrics["ocrTexts"] == 2
    assert outcome.metrics["candidates"] == 2


def test_stage_version_carries_the_merge_axis(
    tmp_path: Path, fake_ner: Callable[[_FakeNer], _FakeNer]
) -> None:
    """Regrouping differently changes the text this stage reads, so it must move the version."""
    from npick_worker.jobs.versions import stage_version
    from npick_worker.ocr.merge import get_merge_config

    fake_ner(_FakeNer())
    outcome = job_adapter.run(_context(tmp_path, _upstream()))
    assert outcome.versions.stage_version == stage_version(
        "entity_extraction", job_adapter.identity()
    )
    assert outcome.versions.prompt_version is None
    assert set(job_adapter.identity()) == {
        "algorithmVersion",
        "configVersion",
        "modelVersion",
        "engineVersion",
        "mergeVersion",
    }
    other = get_merge_config().model_copy(update={"similarity_threshold": 0.5})
    assert job_adapter.identity(other) != job_adapter.identity()


def test_stage_passes_vlm_candidates_through_with_resolved_evidence(
    tmp_path: Path, fake_ner: Callable[[_FakeNer], _FakeNer]
) -> None:
    """§4.3.3 leaves keyframe evidence without a discriminator; the §4.3.6 output has one."""
    fake_ner(_FakeNer())
    upstream = _upstream(
        vlmMetadata={
            "scenes": [
                {
                    "sceneIndex": 0,
                    "tagCandidates": [
                        {
                            "type": "weather",
                            "value": "맑음",
                            "confidence": 0.7,
                            "evidence": [
                                {
                                    "sceneIndex": 0,
                                    "timestampMs": 40,
                                    "storageKey": "runs/1/frames/s0-40.jpg",
                                }
                            ],
                        }
                    ],
                }
            ]
        }
    )
    outcome = job_adapter.run(_context(tmp_path, upstream))

    candidate = outcome.output["scenes"][0]["tagCandidates"][0]
    assert candidate["source"] == "vlm"
    assert candidate["evidence"][0]["sourceRefType"] == "keyframe"
    assert outcome.metrics["vlmCandidates"] == 1


def test_malformed_model_output_is_reported_as_a_permanent_entity_error(
    tmp_path: Path, fake_ner: Callable[[_FakeNer], _FakeNer]
) -> None:
    """Contract §9.2: the whole output is discarded and a retry reads the same weights."""
    from npick_worker.jobs.errors import EntityOutputInvalidError, classify

    # A span past the end of the source text. This value cannot be grounded.
    fake_ner(
        _FakeNer(lambda t: (EntitySpan(label="LCP_CITY", start=0, end=len(t) + 1, confidence=0.9),))
    )
    upstream = _upstream(
        ocr={
            "observations": [_observation(0, "서울역")],
            "keyframesRead": 1,
            "minConfidence": 0.5,
        }
    )
    with pytest.raises(EntityOutputInvalidError) as caught:
        job_adapter.run(_context(tmp_path, upstream))
    assert classify(caught.value, "entity_extraction") == ("ENTITY_SCHEMA_INVALID", False)


def test_missing_weights_stay_transient(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """An unprovisioned pod is not this clip's fault; another attempt can succeed."""
    from npick_worker.entity_extraction.local_ner import EntityModelUnavailableError
    from npick_worker.jobs.errors import ModelUnavailableError, classify

    def unavailable(_config: object) -> _FakeNer:
        raise EntityModelUnavailableError("entity NER requires worker CUDA")

    monkeypatch.setattr(job_adapter, "shared_ner", unavailable)
    with pytest.raises(ModelUnavailableError) as caught:
        job_adapter.run(_context(tmp_path, _upstream()))
    assert classify(caught.value, "entity_extraction") == ("MODEL_UNAVAILABLE", True)


def test_upstream_without_a_scene_list_is_refused(
    tmp_path: Path, fake_ner: Callable[[_FakeNer], _FakeNer]
) -> None:
    """Every candidate is scene-scoped, so a missing scene list is not an empty success."""
    from npick_worker.jobs.errors import UpstreamOutputInvalidError

    fake_ner(_FakeNer())
    with pytest.raises(UpstreamOutputInvalidError):
        job_adapter.run(_context(tmp_path, {}))


def test_vlm_scene_outside_the_scene_list_is_refused(
    tmp_path: Path, fake_ner: Callable[[_FakeNer], _FakeNer]
) -> None:
    from npick_worker.jobs.errors import UpstreamOutputInvalidError

    fake_ner(_FakeNer())
    upstream = _upstream(
        vlmMetadata={
            "scenes": [
                {
                    "sceneIndex": 7,
                    "tagCandidates": [
                        {
                            "type": "season",
                            "value": "겨울",
                            "confidence": 0.7,
                            "evidence": [
                                {
                                    "sceneIndex": 7,
                                    "timestampMs": 40,
                                    "storageKey": "runs/1/frames/s7-40.jpg",
                                }
                            ],
                        }
                    ],
                }
            ]
        }
    )
    with pytest.raises(UpstreamOutputInvalidError):
        job_adapter.run(_context(tmp_path, upstream))


# ── 리뷰 반영: 배선의 세 구멍 ────────────────────────────────────────

_SEGMENTS_KEY = "runs/1/scene_transcript_mapping/a1/transcript_segments.json"
_DECISIONS_KEY = "runs/1/scene_transcript_mapping/a1/transcript_decisions.json"
_SEGMENTS_REF = {
    "kind": "transcript_segments",
    "storageKey": _SEGMENTS_KEY,
    "byteSize": 128,
    "contentHash": "a" * 64,
}
_DECISIONS_REF = {
    "kind": "transcript_decisions",
    "storageKey": _DECISIONS_KEY,
    "byteSize": 128,
    "contentHash": "b" * 64,
}


def _mapping_upstream() -> dict[str, object]:
    payload = _upstream()
    payload["sceneTranscriptMapping"] = {
        "transcript": {"segmentsArtifact": _SEGMENTS_REF, "decisionsArtifact": _DECISIONS_REF},
        "scenes": [
            {
                "sceneIndex": 0,
                "segments": [{"segmentId": "seg-1", "overlapMs": 900}],
                "tokens": "서울역",
            }
        ],
    }
    return payload


def _mapping_documents(upstream: dict[str, object]) -> dict[str, Mapping[str, Any]]:
    """러너가 실제로 받아 오는 것만 준다.

    문서를 손으로 끼워 넣으면 이 단계가 받아 올 목록(`transcript_refs`)에서 빠져 있어도
    테스트가 통과한다 — 그 구멍이 실제로 있었다. 여기서는 받아 올 키를 물어서 그 키만
    채우므로, 목록에서 빠지면 문서가 0장이 되고 이 테스트가 그대로 깨진다.
    """
    from npick_worker.jobs.transcripts import transcript_refs

    documents: dict[str, Mapping[str, Any]] = {
        _SEGMENTS_KEY: {
            "schemaVersion": "npick.transcript.segments/v1",
            "segments": [
                {
                    "segmentId": "seg-1",
                    "s": 0,
                    "e": 900,
                    "t": "서울역에서 만났다",
                    "sourceDetail": "uploaded",
                }
            ],
        },
        _DECISIONS_KEY: {
            "schemaVersion": "npick.transcript.decisions/v1",
            "segmentsArtifact": _SEGMENTS_REF,
            "decisions": [
                {
                    "segmentId": "seg-1",
                    "selected": True,
                    "reasonCode": "PREFERRED_SUBTITLE",
                    "conflictsWith": [],
                }
            ],
        },
    }
    return {
        ref.storage_key: documents[ref.storage_key]
        for ref in transcript_refs(upstream, stage="entity_extraction")
    }


def test_mapping_snapshot_is_fetched_for_this_stage() -> None:
    """이 단계는 `resolve_mapping` 을 부른다. 받아 올 목록에서 빠지면 문서가 0장이다."""
    from npick_worker.jobs.transcripts import transcript_refs

    upstream = _mapping_upstream()
    refs = transcript_refs(upstream, stage="entity_extraction")

    assert [ref.storage_key for ref in refs] == [
        _SEGMENTS_KEY,
        _DECISIONS_KEY,
    ]
    # 매핑 쪽 snapshot 을 보는 다른 단계와 같은 것을 받아야 한다. 상위 `transcript` 별칭은
    # 이전 snapshot 을 가리킬 수 있고, 그것을 받아 오면 문서를 찾지 못해 이 단계가 죽는다.
    assert refs == transcript_refs(upstream, stage="vlm_metadata")


def test_transcript_only_clip_produces_grounded_candidates(
    tmp_path: Path, fake_ner: Callable[[_FakeNer], _FakeNer]
) -> None:
    """OCR 도 VLM 도 없는 정상 입력이다. 매핑만 와도 대사에서 후보가 나와야 한다."""
    ner = fake_ner(
        _FakeNer(lambda t: (EntitySpan(label="LCP_CITY", start=0, end=3, confidence=0.91),))
    )
    upstream = _mapping_upstream()
    ctx = StageContext(
        stage="entity_extraction",
        video_path=None,
        storage_key="clips/1/source.mp4",
        work_dir=tmp_path,
        output_key_prefix="runs/1/entity_extraction/a1/",
        upstream=upstream,
        artifact_documents=_mapping_documents(upstream),
    )
    outcome = job_adapter.run(ctx)

    assert ner.texts == ["서울역에서 만났다"]
    candidate = outcome.output["scenes"][0]["tagCandidates"][0]
    assert (candidate["type"], candidate["value"], candidate["source"]) == (
        "location",
        "서울역",
        "rule",
    )
    assert candidate["evidence"] == [
        {
            "sourceRefType": "scene",
            "sceneIndex": 0,
            "storageKey": _SEGMENTS_KEY,
            "segmentId": "seg-1",
            "s": 0,
            "e": 900,
            "sourceDetail": "uploaded",
        }
    ]
    assert outcome.metrics["transcriptTexts"] == 1


def test_stage_is_not_declared_until_the_weights_are_loaded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """CUDA 만 보면 런타임은 있고 스냅샷은 없는 파드가 배정받아 매 잡마다 죽는다."""
    import torch

    from npick_worker.entity_extraction import local_ner
    from npick_worker.entity_extraction.config import load_config as load_entity_config
    from npick_worker.jobs import registry
    from npick_worker.jobs.errors import ModelUnavailableError

    monkeypatch.setattr(torch.cuda, "is_available", lambda: True)
    monkeypatch.setattr(local_ner, "_SHARED", {})
    with pytest.raises(ModelUnavailableError):
        registry._declared_version("entity_extraction")
    assert "entity_extraction" not in registry.capability_versions()

    config = load_entity_config()
    monkeypatch.setattr(local_ner, "_SHARED", {config.version: local_ner.LocalNer(config)})
    assert registry._declared_version("entity_extraction").startswith(
        "npick.stage.entity_extraction/v1:"
    )


def test_missing_weights_are_not_reported_as_missing_media(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`MEDIA_UNAVAILABLE` 의 뜻은 원본 영상이 없다는 것이다. 모델 배치 문제와 갈라야 한다."""
    pytest.importorskip("transformers")
    import torch

    from npick_worker.entity_extraction.local_ner import EntityModelUnavailableError, LocalNer
    from npick_worker.jobs.errors import classify

    monkeypatch.setattr(torch.cuda, "is_available", lambda: True)
    absent = load_config().model_copy(update={"revision": "0" * 40})
    with pytest.raises(EntityModelUnavailableError):
        LocalNer(absent).load()
    # 순수 계층의 예외는 잡 어댑터가 번역한다. 번역 전 값이 미디어 오류가 아니어야 한다.
    assert classify(EntityModelUnavailableError("x"), "entity_extraction")[0] != "MEDIA_UNAVAILABLE"
