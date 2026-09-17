"""Pure postprocessing: validate the whole inference, then create grounded candidates."""

import unicodedata
from collections.abc import Mapping, Sequence

from pydantic import Field

from npick_worker.entity_extraction.schema import (
    Candidate,
    EntityType,
    Evidence,
    Output,
    SceneCandidates,
    StrictModel,
)


class EntitySchemaInvalidError(ValueError):
    """ENTITY_SCHEMA_INVALID: no field from this stage may be used."""


class TextInput(StrictModel):
    """One OCR merge representative or one selected, scene-linked transcript segment.

    OCR evidence points at the representative observation whose exact text is used;
    other merge members remain discoverable in the immutable upstream OCR artifact.
    """

    label: str = Field(min_length=1)
    text: str = Field(min_length=1)
    evidence: Evidence


class EntitySpan(StrictModel):
    label: str = Field(min_length=1)
    start: int = Field(ge=0)
    end: int = Field(gt=0)
    confidence: float = Field(ge=0, le=1, allow_inf_nan=False)


class SceneInput(StrictModel):
    scene_index: int = Field(ge=0)
    texts: tuple[TextInput, ...] = ()
    vlm_candidates: tuple[Candidate, ...] = ()


def match_key(value: str) -> str:
    """F-04 comparison only. Never replace the display value with this key."""
    folded = unicodedata.normalize("NFKC", value)
    return unicodedata.normalize(
        "NFKC", "".join(c for c in folded if not c.isspace() and c not in "\u200b\ufeff\u00ad")
    )


def extract(
    scenes: Sequence[SceneInput],
    predictions: Mapping[tuple[int, str], Sequence[EntitySpan]],
    *,
    label_map: Mapping[str, EntityType | None],
    minimum_confidence: float,
) -> Output:
    """All predictions must be present, including empty ones; malformed output fails atomically.

    No source text is sent to a model here. The local adapter supplies spans with original
    Unicode codepoint offsets. Unsupported NER categories are explicitly mapped to None.
    Keep independent rule/VLM evidence: their scores are not calibrated against each other.
    """
    if not 0 <= minimum_confidence <= 1:
        raise ValueError("invalid confidence threshold")
    expected = {(s.scene_index, t.label) for s in scenes for t in s.texts}
    if set(predictions) != expected:
        raise EntitySchemaInvalidError("missing or unexpected text predictions")
    result = []
    for scene in scenes:
        if len({t.label for t in scene.texts}) != len(scene.texts):
            raise ValueError("duplicate text label")
        candidates = list(scene.vlm_candidates)
        if any(c.source != "vlm" for c in candidates):
            raise ValueError("upstream candidate source must be vlm")
        for text in scene.texts:
            if text.evidence.sceneIndex != scene.scene_index:
                raise ValueError("cross-scene input")
            for span in predictions[(scene.scene_index, text.label)]:
                if span.label not in label_map or not 0 <= span.start < span.end <= len(text.text):
                    raise EntitySchemaInvalidError("invalid NER label or source span")
                tag_type = label_map[span.label]
                value = text.text[span.start : span.end]
                if tag_type is None or span.confidence < minimum_confidence:
                    continue
                # Only spans that are actually emitted are validated. Half the KPF labels map
                # to None, so failing the whole clip over a span this stage discards anyway
                # would turn a category we never use into a permanent stage failure.
                if not match_key(value):
                    raise EntitySchemaInvalidError("empty entity display value")
                candidates.append(
                    Candidate(
                        type=tag_type,
                        value=value,
                        source="rule",
                        confidence=round(span.confidence, 4),
                        evidence=(text.evidence,),
                    )
                )
        # Combine only equal types, exact F-04 keys and the same extraction source.
        merged: dict[tuple[str, str, str], Candidate] = {}
        for candidate in candidates:
            key = (candidate.type, match_key(candidate.value), candidate.source)
            if not key[1]:
                raise EntitySchemaInvalidError("empty candidate match key")
            previous = merged.get(key)
            if previous is None:
                merged[key] = candidate
            else:
                refs = tuple(dict.fromkeys((*previous.evidence, *candidate.evidence)))
                merged[key] = Candidate(
                    type=previous.type,
                    value=previous.value,
                    source=previous.source,
                    confidence=max(previous.confidence, candidate.confidence),
                    evidence=refs,
                )
        result.append(
            SceneCandidates(sceneIndex=scene.scene_index, tagCandidates=tuple(merged.values()))
        )
    return Output(scenes=tuple(result))
