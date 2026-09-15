"""최종 대사 연결 결과를 VLM 입력으로 옮긴다. 선택·매핑 알고리즘은 실행하지 않는다."""

from collections.abc import Mapping, Sequence
from dataclasses import replace
from typing import Any

from npick_worker.jobs.errors import UpstreamOutputInvalidError
from npick_worker.jobs.transcripts import (
    TranscriptDecisions,
    TranscriptSegments,
    parse_scene_transcript_mapping,
    validate_snapshot,
)
from npick_worker.vlm_metadata.grounding import TranscriptRef, TranscriptText
from npick_worker.vlm_metadata.models import SceneKeyframes


def attach_mapped_transcripts(
    scenes: Sequence[SceneKeyframes],
    upstream: Mapping[str, Any],
    documents: Mapping[str, Mapping[str, Any]],
) -> tuple[SceneKeyframes, ...]:
    if "scene_transcript_mapping" not in upstream:
        return tuple(scenes)
    try:
        mapping = parse_scene_transcript_mapping(upstream["scene_transcript_mapping"])
        snapshot = mapping.transcript
        segments = TranscriptSegments.model_validate(
            documents[snapshot.segments_artifact.storage_key]
        )
        decisions = TranscriptDecisions.model_validate(
            documents[snapshot.decisions_artifact.storage_key]
        )
        validate_snapshot(snapshot.segments_artifact, segments, decisions)
        linked = {scene.scene_index: scene.segments for scene in mapping.scenes}
        if set(linked) != {scene.scene_index for scene in scenes}:
            raise ValueError("mapped scenes must match frame extraction scenes")
        originals = {segment.segment_id: segment for segment in segments.segments}
        selected = {decision.segment_id for decision in decisions.decisions if decision.selected}
        result: list[SceneKeyframes] = []
        for scene in scenes:
            texts: list[TranscriptText] = []
            for link in linked[scene.scene_index]:
                if link.segment_id not in selected:
                    raise ValueError("mapped segment must be selected")
                segment = originals[link.segment_id]
                if link.overlap_ms > segment.e - segment.s:
                    raise ValueError("overlap exceeds original segment duration")
                texts.append(
                    TranscriptText(
                        ref=TranscriptRef(
                            scene_index=scene.scene_index,
                            storage_key=snapshot.segments_artifact.storage_key,
                            segment_id=segment.segment_id,
                            s=segment.s,
                            e=segment.e,
                            source_detail=segment.source_detail,
                        ),
                        t=segment.t,
                    )
                )
            result.append(replace(scene, transcripts=tuple(texts)))
        return tuple(result)
    except (ValueError, KeyError, TypeError) as exc:
        raise UpstreamOutputInvalidError("invalid scene transcript mapping or snapshot") from exc
