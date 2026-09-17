"""Adapt scene entity extraction to the job contract. Inference stays on this worker's GPU.

The pure module (`npick_worker.entity_extraction`) knows nothing about the job API, so
everything that turns `inputs.upstream` into its dataclasses lives here — the boundary
`ai/AGENTS.md` draws and `jobs/scene_transcript_mapping.py` already follows.

**The OCR merge runs here, not upstream.** BE rebuilds `inputs.upstream.ocr` from
`ocr_observation` rows and that table has no group column (`jobs/models.py`
`UpstreamOcrOutput`), so `textGroups` cannot come back. This stage regroups the array it
was handed with the same default merge config the OCR stage used, which is why
`mergeVersion` is one of this stage's reproducibility axes — regroup differently and the
same observations produce different text with nothing else in the tuple moving.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Literal

from pydantic import Field

from npick_worker.entity_extraction.config import Config, get_default_config
from npick_worker.entity_extraction.extractor import (
    EntitySchemaInvalidError,
    EntitySpan,
    SceneInput,
    extract,
)
from npick_worker.entity_extraction.inputs import build_inputs
from npick_worker.entity_extraction.local_ner import EntityModelUnavailableError, shared_ner
from npick_worker.entity_extraction.local_ner import identity as ner_identity
from npick_worker.entity_extraction.schema import (
    Candidate,
    Evidence,
    KeyframeEvidence,
    OcrEvidence,
    TranscriptEvidence,
)
from npick_worker.jobs.errors import (
    EntityOutputInvalidError,
    ModelUnavailableError,
    UpstreamOutputInvalidError,
)
from npick_worker.jobs.models import UpstreamOcrOutput, UpstreamSceneOut, WireResponse
from npick_worker.jobs.registry import StageContext, StageOutcome, _parse_upstream, _runtime
from npick_worker.jobs.transcripts import resolve_mapping
from npick_worker.jobs.versions import StageVersion, output_schema_version, stage_version
from npick_worker.ocr.merge import (
    OcrMergeConfig,
    OcrTextGroup,
    get_merge_config,
    merge_observations,
)
from npick_worker.ocr.models import BoundingBox, KeyframeRef, OcrObservation
from npick_worker.scene_transcript_mapping import Decision, MappingResult, SceneLinks, Segment


class _Scenes(WireResponse):
    """The canonical scene list. Only the indices are read; intervals belong upstream."""

    scenes: list[UpstreamSceneOut] = Field(min_length=1)


class _KeyframeEvidenceIn(WireResponse):
    """§4.3.3 leaves `sourceRefType` implicit on keyframe evidence. Restore it on the way in."""

    source_ref_type: Literal["keyframe"] = "keyframe"
    scene_index: int = Field(ge=0)
    timestamp_ms: int = Field(ge=0)
    storage_key: str = Field(min_length=1)


class _OcrEvidenceIn(WireResponse):
    source_ref_type: Literal["ocr_observation"]
    scene_index: int = Field(ge=0)
    timestamp_ms: int = Field(ge=0)
    storage_key: str = Field(min_length=1)
    observation_index: int = Field(ge=0)


class _TranscriptEvidenceIn(WireResponse):
    source_ref_type: Literal["scene"]
    scene_index: int = Field(ge=0)
    storage_key: str = Field(min_length=1)
    segment_id: str = Field(min_length=1)
    s: int = Field(ge=0)
    e: int = Field(gt=0)
    source_detail: Literal["uploaded", "embedded", "asr"]


_EvidenceIn = _OcrEvidenceIn | _TranscriptEvidenceIn | _KeyframeEvidenceIn


class _TagCandidateIn(WireResponse):
    """A VLM candidate on its way back through BE. Same vocabulary, looser about extras.

    `TagCandidateOut` is what the VLM stage *sends*; this is what comes back, which may
    have travelled through storage. Everything declared here is read, and the strictness
    that matters — the type vocabulary, a finite 0~1 score, at least one evidence
    reference — is declared. What is not declared is refused a veto.
    """

    type: Literal[
        "person",
        "organization",
        "location",
        "facility",
        "keyword",
        "event",
        "season",
        "weather",
        "scene_type",
    ]
    value: str = Field(min_length=1)
    confidence: float = Field(ge=0, le=1)
    evidence: Sequence[_EvidenceIn] = Field(min_length=1)


class _SceneTags(WireResponse):
    scene_index: int = Field(ge=0)
    #: Absent when BE cannot return candidates yet. **Not the same as a scene that has
    #: none** — both arrive here as no candidates, and the `vlmCandidates` metric is what
    #: tells the two apart in `stage_states_json` after the fact.
    tag_candidates: Sequence[_TagCandidateIn] = ()


class _Vlm(WireResponse):
    scenes: Sequence[_SceneTags] = Field(min_length=1)


class EntityExtractionUpstream(WireResponse):
    """`scene_detection` is required; every text-bearing upstream is optional.

    Stage 8 is non-fatal and so are three of its four upstreams. A clip whose OCR failed
    still has dialogue worth extracting, and demanding OCR here would turn one non-fatal
    failure into two. What is *not* optional is the scene list — without it this stage
    cannot say which scene a candidate belongs to, and every candidate is scene-scoped.
    """

    scene_detection: _Scenes
    ocr: UpstreamOcrOutput | None = None
    vlm_metadata: _Vlm | None = None


@dataclass(frozen=True, slots=True)
class _Ocr:
    """`entity_extraction.inputs.OcrSource` over the array BE handed back."""

    observations: tuple[OcrObservation, ...]
    text_groups: tuple[OcrTextGroup, ...]


def identity(merge: OcrMergeConfig | None = None, config: Config | None = None) -> dict[str, str]:
    """This stage's reproducibility tuple. Five axes.

    Four come from the model side (`local_ner.identity`): the algorithm, the config hash
    that covers the TOML and the whole BIO table, the pinned weights, and the runtime that
    executes them. The fifth is the OCR merge config, for the reason in the module
    docstring. There is no prompt axis — this stage does not prompt anything.
    """
    return {
        **ner_identity(config if config is not None else get_default_config()),
        "mergeVersion": (merge if merge is not None else get_merge_config()).version_id,
    }


def _observation(row: Any) -> OcrObservation:
    return OcrObservation(
        keyframe=KeyframeRef(
            scene_index=row.scene_index,
            timestamp_ms=row.timestamp_ms,
            storage_key=row.storage_key,
        ),
        raw_text=row.raw_text,
        # `tokens` is stored as the whitespace-joined string (`ocr_observation.tokens`).
        # Split it back instead of retokenizing: this stage must not need Kiwi, and
        # running it again could produce a different split than the one that was stored.
        tokens=tuple(row.tokens.split()),
        confidence=row.confidence,
        box=BoundingBox(points=tuple(tuple(point) for point in row.bounding_box.points)),
        unverified=row.unverified,
        text_key=row.text_key,
    )


def _evidence(row: _EvidenceIn) -> Evidence:
    if isinstance(row, _OcrEvidenceIn):
        return OcrEvidence(
            sourceRefType="ocr_observation",
            sceneIndex=row.scene_index,
            timestampMs=row.timestamp_ms,
            storageKey=row.storage_key,
            observationIndex=row.observation_index,
        )
    if isinstance(row, _TranscriptEvidenceIn):
        return TranscriptEvidence(
            sourceRefType="scene",
            sceneIndex=row.scene_index,
            storageKey=row.storage_key,
            segmentId=row.segment_id,
            s=row.s,
            e=row.e,
            sourceDetail=row.source_detail,
        )
    return KeyframeEvidence(
        sourceRefType="keyframe",
        sceneIndex=row.scene_index,
        timestampMs=row.timestamp_ms,
        storageKey=row.storage_key,
    )


def _mapping(
    upstream: Mapping[str, Any], documents: Mapping[str, Mapping[str, Any]]
) -> tuple[MappingResult, str] | None:
    """Rebuild the mapper's own result from the validated payload. Nothing is re-decided.

    `resolve_mapping` has already refused links to unselected or overlong segments, so
    every segment reaching here is a selected one — which is why the rebuilt decisions are
    all `selected=True`. Excluded originals are not carried: this stage never sees them,
    and that is the point. An excluded subtitle must not come back as tag evidence.
    """
    resolved = resolve_mapping(upstream, documents)
    if resolved is None:
        return None
    mapping, linked = resolved
    segments = tuple(
        Segment(row.segment_id, row.s, row.e, row.t, row.source_detail) for row in linked.values()
    )
    return (
        MappingResult(
            segments=segments,
            decisions=tuple(Decision(segment.id, True, ()) for segment in segments),
            scenes=tuple(
                SceneLinks(
                    scene.scene_index,
                    tuple((link.segment_id, link.overlap_ms) for link in scene.segments),
                )
                for scene in mapping.scenes
            ),
        ),
        mapping.transcript.segments_artifact.storage_key,
    )


def _inputs(
    ctx: StageContext, upstream: EntityExtractionUpstream, merge: OcrMergeConfig
) -> tuple[SceneInput, ...]:
    ocr: _Ocr | None = None
    if upstream.ocr is not None:
        observations = tuple(_observation(row) for row in upstream.ocr.observations)
        ocr = _Ocr(observations, merge_observations(observations, merge))
    mapped = _mapping(ctx.upstream, ctx.artifact_documents)
    scenes = build_inputs(
        [scene.scene_index for scene in upstream.scene_detection.scenes],
        ocr=ocr,
        transcripts=None if mapped is None else mapped[0],
        transcript_storage_key="" if mapped is None else mapped[1],
    )
    if upstream.vlm_metadata is None:
        return scenes
    # `build_inputs` takes VLM candidates as a `VlmResult`, and BE cannot return one — it
    # stores tags, not the stage's result object. Attach the parsed candidates here rather
    # than assembling a fake result, keeping the scene-set check `build_inputs` would run.
    candidates = {
        scene.scene_index: tuple(
            Candidate(
                type=tag.type,
                value=tag.value,
                source="vlm",
                confidence=tag.confidence,
                evidence=tuple(_evidence(ref) for ref in tag.evidence),
            )
            for tag in scene.tag_candidates
        )
        for scene in upstream.vlm_metadata.scenes
    }
    if len(candidates) != len(upstream.vlm_metadata.scenes):
        raise ValueError("duplicate VLM scene")
    if set(candidates) - {scene.scene_index for scene in scenes}:
        raise ValueError("VLM scene is not in this input")
    return tuple(
        SceneInput(
            scene_index=scene.scene_index,
            texts=scene.texts,
            vlm_candidates=candidates.get(scene.scene_index, ()),
        )
        for scene in scenes
    )


def run(ctx: StageContext) -> StageOutcome:
    upstream = _parse_upstream(EntityExtractionUpstream, ctx.upstream)
    merge = get_merge_config()
    config = get_default_config()
    try:
        scenes = _inputs(ctx, upstream, merge)
    except (ValueError, KeyError, TypeError) as exc:
        raise UpstreamOutputInvalidError("invalid entity extraction input") from exc

    try:
        # Loads once per process; the warm-up has usually done it already.
        ner = shared_ner(config)
        predictions: dict[tuple[int, str], tuple[EntitySpan, ...]] = {
            (scene.scene_index, text.label): ner.predict(text.text)
            for scene in scenes
            for text in scene.texts
        }
        output = extract(
            scenes,
            predictions,
            label_map=config.label_map,
            minimum_confidence=config.minimum_confidence,
        )
    except EntityModelUnavailableError as exc:
        # Not this clip's problem — another pod or the next attempt can succeed
        # (contract §9.2, transient).
        raise ModelUnavailableError(str(exc)) from exc
    except EntitySchemaInvalidError as exc:
        # The model broke its own contract. No field of this stage may be used, and a
        # retry reads the same weights over the same text (contract §4.3.6).
        raise EntityOutputInvalidError(str(exc)) from exc
    except (ValueError, KeyError, TypeError) as exc:
        raise UpstreamOutputInvalidError("invalid entity extraction input") from exc

    reproducibility = identity(merge, config)
    detail = {key: value for key, value in reproducibility.items() if key != "configVersion"}
    return StageOutcome(
        output=output.model_dump(mode="json"),
        versions=StageVersion(
            stage_version=stage_version(ctx.stage, reproducibility),
            output_schema_version=output_schema_version(ctx.stage),
            config_version=config.version,
            model_version=f"{config.model}@{config.revision}",
            # No prompt. The key stays so that "this stage has none" and "we forgot to
            # report one" remain different facts (`jobs/versions.py`).
            prompt_version=None,
            detail=detail,
            runtime=_runtime(),
        ),
        metrics={
            "scenes": len(output.scenes),
            "candidates": sum(len(scene.tagCandidates) for scene in output.scenes),
            # Which upstream actually supplied text. Zero candidates out of 0 texts and
            # zero out of 300 are different facts; only these tell them apart later.
            "ocrTexts": sum(
                1
                for scene in scenes
                for text in scene.texts
                if text.evidence.sourceRefType == "ocr_observation"
            ),
            "transcriptTexts": sum(
                1
                for scene in scenes
                for text in scene.texts
                if text.evidence.sourceRefType == "scene"
            ),
            "vlmCandidates": sum(len(scene.vlm_candidates) for scene in scenes),
        },
    )
