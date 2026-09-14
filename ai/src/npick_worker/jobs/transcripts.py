"""Approved transcript artifact boundary; selection and ASR remain stage implementations."""

from collections.abc import Mapping
from typing import Any, Literal

from pydantic import Field, StrictBool, StrictInt, model_validator

from npick_worker.jobs.models import ArtifactRef
from npick_worker.jobs.versions import WireModel


class TranscriptSegment(WireModel):
    segment_id: str = Field(min_length=1)
    s: StrictInt = Field(ge=0)
    e: StrictInt = Field(gt=0)
    t: str = Field(min_length=1)
    source_detail: Literal["uploaded", "embedded", "asr"]

    @model_validator(mode="after")
    def interval(self) -> "TranscriptSegment":
        if self.e <= self.s or not self.t.strip():
            raise ValueError("invalid transcript interval or text")
        return self


class TranscriptSegments(WireModel):
    schema_version: Literal["npick.transcript.segments/v1"]
    segments: list[TranscriptSegment]

    @model_validator(mode="after")
    def unique_ids(self) -> "TranscriptSegments":
        if len({s.segment_id for s in self.segments}) != len(self.segments):
            raise ValueError("duplicate segmentId")
        return self


class TranscriptDecision(WireModel):
    segment_id: str
    selected: StrictBool
    reason_code: Literal["PREFERRED_SUBTITLE", "ASR_SUPPLEMENT", "OVERLAPS_HIGHER_PRIORITY"]
    conflicts_with: list[str]


class TranscriptDecisions(WireModel):
    schema_version: Literal["npick.transcript.decisions/v1"]
    segments_artifact: ArtifactRef
    decisions: list[TranscriptDecision]


class TranscriptSnapshot(WireModel):
    """원본과 최종 채택 결과의 기존 artifact 참조."""

    segments_artifact: ArtifactRef
    decisions_artifact: ArtifactRef

    @model_validator(mode="after")
    def artifact_kinds(self) -> "TranscriptSnapshot":
        if self.segments_artifact.kind != "transcript_segments":
            raise ValueError("invalid segments artifact kind")
        if self.decisions_artifact.kind != "transcript_decisions":
            raise ValueError("invalid decisions artifact kind")
        if self.segments_artifact.storage_key == self.decisions_artifact.storage_key:
            raise ValueError("snapshot artifacts must have distinct storage keys")
        return self


class MappedSegment(WireModel):
    segment_id: str = Field(min_length=1)
    overlap_ms: StrictInt = Field(gt=0)


class SceneTranscriptLinks(WireModel):
    scene_index: StrictInt = Field(ge=0)
    segments: list[MappedSegment]

    @model_validator(mode="after")
    def unique_links(self) -> "SceneTranscriptLinks":
        ids = [segment.segment_id for segment in self.segments]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate segment in scene")
        return self


class SceneTranscriptMappingOutput(WireModel):
    """장면 연결 결과 계약. 선택·겹침 계산은 생산 단계가 담당한다."""

    transcript: TranscriptSnapshot
    scenes: list[SceneTranscriptLinks] = Field(min_length=1)

    @model_validator(mode="after")
    def unique_scenes(self) -> "SceneTranscriptMappingOutput":
        ids = [scene.scene_index for scene in self.scenes]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate scene in transcript mapping")
        return self


def validate_snapshot(
    segments_ref: ArtifactRef,
    segments: TranscriptSegments,
    decisions: TranscriptDecisions,
) -> None:
    """Validate references and reported decisions without running a selection algorithm."""
    if decisions.segments_artifact != segments_ref:
        raise ValueError("decisions refer to a different snapshot")
    originals = {s.segment_id: s for s in segments.segments}
    ids = [d.segment_id for d in decisions.decisions]
    if len(set(ids)) != len(ids) or set(ids) != set(originals):
        raise ValueError("each original requires exactly one decision")
    priority = {"uploaded": 0, "embedded": 1, "asr": 2}
    for decision in decisions.decisions:
        segment = originals[decision.segment_id]
        if len(set(decision.conflicts_with)) != len(decision.conflicts_with):
            raise ValueError("duplicate conflict")
        if decision.selected:
            expected = "ASR_SUPPLEMENT" if segment.source_detail == "asr" else "PREFERRED_SUBTITLE"
            if decision.reason_code != expected or decision.conflicts_with:
                raise ValueError("selected decision has invalid reason or conflicts")
        elif decision.reason_code != "OVERLAPS_HIGHER_PRIORITY" or not decision.conflicts_with:
            raise ValueError("excluded decision requires overlap evidence")
        for conflict_id in decision.conflicts_with:
            conflict = originals.get(conflict_id)
            if (
                conflict is None
                or priority[conflict.source_detail] >= priority[segment.source_detail]
                or max(segment.s, conflict.s) >= min(segment.e, conflict.e)
            ):
                raise ValueError("invalid higher-priority overlap reference")


def transcript_refs(
    upstream: Mapping[str, Any], *, stage: str | None = None
) -> tuple[ArtifactRef, ...]:
    # 상위 transcript 별칭이 이전 snapshot이어도 최종 매핑의 참조가 정본이다.
    if stage == "vlm_metadata" and "scene_transcript_mapping" in upstream:
        mapping = SceneTranscriptMappingOutput.model_validate(upstream["scene_transcript_mapping"])
        return (mapping.transcript.segments_artifact, mapping.transcript.decisions_artifact)
    transcript = upstream.get("transcript")
    if transcript is None:
        return ()
    if not isinstance(transcript, dict) or "segmentsArtifact" not in transcript:
        raise ValueError("transcript requires segmentsArtifact")
    refs = [ArtifactRef.model_validate(transcript["segmentsArtifact"])]
    if refs[0].kind != "transcript_segments":
        raise ValueError("invalid segments artifact kind")
    if "decisionsArtifact" in transcript:
        refs.append(ArtifactRef.model_validate(transcript["decisionsArtifact"]))
        if refs[1].kind != "transcript_decisions":
            raise ValueError("invalid decisions artifact kind")
    return tuple(refs)
