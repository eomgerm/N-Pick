"""Pure conversion of successful upstream stage results; no downloads or job wiring."""

from collections.abc import Sequence

from npick_worker.entity_extraction.extractor import SceneInput, TextInput
from npick_worker.entity_extraction.schema import (
    Candidate,
    Evidence,
    KeyframeEvidence,
    OcrEvidence,
    TranscriptEvidence,
)
from npick_worker.ocr.models import OcrResult
from npick_worker.scene_transcript_mapping import MappingResult
from npick_worker.vlm_metadata.grounding import OcrRef, TranscriptRef
from npick_worker.vlm_metadata.models import KeyframeRef, VlmResult


def vlm_evidence(ref: KeyframeRef | OcrRef | TranscriptRef) -> Evidence:
    if isinstance(ref, OcrRef):
        return OcrEvidence(
            sourceRefType="ocr_observation",
            sceneIndex=ref.scene_index,
            timestampMs=ref.timestamp_ms,
            storageKey=ref.storage_key,
            observationIndex=ref.observation_index,
        )
    if isinstance(ref, TranscriptRef):
        return TranscriptEvidence(
            sourceRefType="scene",
            sceneIndex=ref.scene_index,
            storageKey=ref.storage_key,
            segmentId=ref.segment_id,
            s=ref.s,
            e=ref.e,
            sourceDetail=ref.source_detail,
        )
    return KeyframeEvidence(
        sourceRefType="keyframe",
        sceneIndex=ref.scene_index,
        timestampMs=ref.timestamp_ms,
        storageKey=ref.storage_key,
    )


def build_inputs(
    scene_indices: Sequence[int],
    *,
    ocr: OcrResult | None = None,
    transcripts: MappingResult | None = None,
    transcript_storage_key: str = "",
    vlm: VlmResult | None = None,
) -> tuple[SceneInput, ...]:
    """None means unavailable upstream, never reinterpret malformed data as empty success.

    The caller supplies results from the same clip/run, validated by their owning stages.
    Transcript segments are consumed only through selected scene links; a clip-wide script
    cannot enter here. Original OCR observations and VLM evidence are not regenerated.
    """
    if len(set(scene_indices)) != len(scene_indices):
        raise ValueError("duplicate scene index")
    texts: dict[int, list[TextInput]] = {index: [] for index in scene_indices}
    candidates: dict[int, list[Candidate]] = {index: [] for index in scene_indices}
    if ocr is not None:
        observations = ocr.observations
        for index, group in enumerate(ocr.text_groups):
            item = observations[group.representative_index]
            if group.scene_index not in texts or item.keyframe.scene_index != group.scene_index:
                raise ValueError("OCR group scene is not in this input")
            texts[group.scene_index].append(
                TextInput(
                    label=f"ocr_{index + 1}",
                    text=item.raw_text,
                    evidence=OcrEvidence(
                        sourceRefType="ocr_observation",
                        sceneIndex=group.scene_index,
                        timestampMs=item.keyframe.timestamp_ms,
                        storageKey=item.keyframe.storage_key,
                        observationIndex=group.representative_index,
                    ),
                )
            )
    if transcripts is not None:
        if not transcript_storage_key:
            raise ValueError("transcript snapshot key required")
        if {s.index for s in transcripts.scenes} != set(scene_indices):
            raise ValueError("transcript scene set differs")
        selected = {d.segment_id for d in transcripts.decisions if d.selected}
        segments = {s.id: s for s in transcripts.segments}
        for scene in transcripts.scenes:
            for i, (segment_id, overlap) in enumerate(scene.links):
                if segment_id not in selected or overlap <= 0:
                    raise ValueError("unselected or nonoverlapping transcript")
                segment = segments[segment_id]
                texts[scene.index].append(
                    TextInput(
                        label=f"tr_{i + 1}",
                        text=segment.text,
                        evidence=TranscriptEvidence(
                            sourceRefType="scene",
                            sceneIndex=scene.index,
                            storageKey=transcript_storage_key,
                            segmentId=segment.id,
                            s=segment.start,
                            e=segment.end,
                            sourceDetail=segment.source,
                        ),
                    )
                )
    if vlm is not None:
        if len({s.scene_index for s in vlm.scenes}) != len(vlm.scenes):
            raise ValueError("duplicate VLM scene")
        for vlm_scene in vlm.scenes:
            if vlm_scene.scene_index not in candidates:
                raise ValueError("VLM scene is not in this input")
            candidates[vlm_scene.scene_index].extend(
                Candidate(
                    type=tag.type,
                    value=tag.value,
                    source="vlm",
                    confidence=tag.confidence,
                    evidence=tuple(vlm_evidence(ref) for ref in tag.evidence),
                )
                for tag in vlm_scene.tag_candidates
            )
    return tuple(
        SceneInput(
            scene_index=index,
            texts=tuple(texts[index]),
            vlm_candidates=tuple(candidates[index]),
        )
        for index in scene_indices
    )
