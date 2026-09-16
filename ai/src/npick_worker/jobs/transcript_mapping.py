"""Adapt final transcript mapping to the existing artifact and VLM contracts."""

import hashlib
from collections.abc import Mapping
from typing import Any, Literal

from pydantic import Field, StrictInt

from npick_worker.jobs.errors import UpstreamOutputInvalidError
from npick_worker.jobs.models import ArtifactRef, WireResponse
from npick_worker.jobs.registry import PendingUpload, StageContext, StageOutcome
from npick_worker.jobs.transcripts import (
    MappedSegment,
    SceneTranscriptLinks,
    SceneTranscriptMappingOutput,
    TranscriptDecision,
    TranscriptDecisions,
    TranscriptSegment,
    TranscriptSegments,
    TranscriptSnapshot,
    validate_snapshot,
)
from npick_worker.jobs.versions import StageVersion, output_schema_version, stage_version
from npick_worker.scene_transcript_mapping import Scene, Segment, map_transcripts
from npick_worker.scene_transcript_mapping.mapper import ALGORITHM_VERSION
from npick_worker.versioning import canonical_json


class _Scene(WireResponse):
    scene_index: StrictInt = Field(ge=0)
    start_time_ms: StrictInt = Field(ge=0)
    end_time_ms: StrictInt = Field(gt=0)


class _Scenes(WireResponse):
    scenes: list[_Scene] = Field(min_length=1)


class _AsrSegment(TranscriptSegment):
    model_config = WireResponse.model_config
    source_detail: Literal["asr"]


class _Asr(WireResponse):
    segments: list[_AsrSegment]
    reason_code: Literal["NO_SPEECH_DETECTED"] | None = None


def identity() -> dict[str, str]:
    return {"algorithm": ALGORITHM_VERSION}


def _upload(ctx: StageContext, kind: str, payload: Mapping[str, Any]) -> PendingUpload:
    data = canonical_json(payload).encode("utf-8")
    name = f"{kind}.json"
    path = ctx.work_dir / name
    path.write_bytes(data)
    return PendingUpload(
        ref=ArtifactRef(
            kind=kind,
            storage_key=f"{ctx.output_key_prefix.rstrip('/')}/{name}",
            byte_size=len(data),
            content_hash=hashlib.sha256(data).hexdigest(),
        ),
        local_path=path,
        content_type="application/json",
    )


def run(ctx: StageContext) -> StageOutcome:
    try:
        scene_input = _Scenes.model_validate(ctx.upstream["sceneDetection"])
        originals: list[TranscriptSegment] = []
        if "transcript" in ctx.upstream:
            # The alias may also contain selection diagnostics: receiving ignores them.
            refs = ctx.upstream["transcript"]
            snapshot = TranscriptSnapshot.model_validate(
                {
                    "segmentsArtifact": refs["segmentsArtifact"],
                    "decisionsArtifact": refs["decisionsArtifact"],
                }
            )
            original = TranscriptSegments.model_validate(
                ctx.artifact_documents[snapshot.segments_artifact.storage_key]
            )
            decisions = TranscriptDecisions.model_validate(
                ctx.artifact_documents[snapshot.decisions_artifact.storage_key]
            )
            validate_snapshot(snapshot.segments_artifact, original, decisions)
            originals.extend(original.segments)
        if "asr" in ctx.upstream:
            asr = _Asr.model_validate(ctx.upstream["asr"])
            if len({s.segment_id for s in asr.segments}) != len(asr.segments):
                raise ValueError("duplicate ASR segment ID")
            if asr.reason_code == "NO_SPEECH_DETECTED" and asr.segments:
                raise ValueError("no-speech result contains segments")
            ids = {s.segment_id: s for s in originals}
            for segment in asr.segments:
                normalized = TranscriptSegment.model_validate(segment.model_dump())
                if segment.segment_id in ids:
                    if ids[segment.segment_id] != normalized:
                        raise ValueError("ASR segment ID collides with an original")
                    continue
                ids[segment.segment_id] = normalized
                originals.append(normalized)
        result = map_transcripts(
            [Scene(s.scene_index, s.start_time_ms, s.end_time_ms) for s in scene_input.scenes],
            [Segment(s.segment_id, s.s, s.e, s.t, s.source_detail) for s in originals],
        )
    except (ValueError, KeyError, TypeError) as exc:
        raise UpstreamOutputInvalidError(
            "invalid scene mapping input or transcript snapshot"
        ) from exc

    segments = TranscriptSegments(
        schema_version="npick.transcript.segments/v1",
        segments=[
            TranscriptSegment(segment_id=s.id, s=s.start, e=s.end, t=s.text, source_detail=s.source)
            for s in result.segments
        ],
    )
    segment_upload = _upload(ctx, "transcript_segments", segments.model_dump(by_alias=True))
    sources = {s.id: s.source for s in result.segments}
    decisions = TranscriptDecisions(
        schema_version="npick.transcript.decisions/v1",
        segments_artifact=segment_upload.ref,
        decisions=[
            TranscriptDecision(
                segment_id=d.segment_id,
                selected=d.selected,
                reason_code=(
                    "OVERLAPS_HIGHER_PRIORITY"
                    if not d.selected
                    else "ASR_SUPPLEMENT"
                    if sources[d.segment_id] == "asr"
                    else "PREFERRED_SUBTITLE"
                ),
                conflicts_with=list(d.conflicts),
            )
            for d in result.decisions
        ],
    )
    decision_upload = _upload(ctx, "transcript_decisions", decisions.model_dump(by_alias=True))
    output = SceneTranscriptMappingOutput(
        transcript=TranscriptSnapshot(
            segments_artifact=segment_upload.ref, decisions_artifact=decision_upload.ref
        ),
        scenes=[
            SceneTranscriptLinks(
                scene_index=index,
                segments=[MappedSegment(segment_id=id_, overlap_ms=ms) for id_, ms in links],
            )
            for index, links in result.scenes
        ],
    )
    return StageOutcome(
        output=output.model_dump(by_alias=True),
        versions=StageVersion(
            stage_version=stage_version(ctx.stage, identity()),
            output_schema_version=output_schema_version(ctx.stage),
            detail=identity(),
        ),
        # The runner adds these refs to artifacts only after successful upload.
        uploads=(segment_upload, decision_upload),
    )
