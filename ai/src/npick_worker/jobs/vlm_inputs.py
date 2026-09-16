"""최종 대사 연결 결과를 VLM 입력으로 옮긴다. 선택·매핑 알고리즘은 실행하지 않는다."""

from collections.abc import Mapping, Sequence
from dataclasses import replace
from typing import Any

from npick_worker.jobs.errors import UpstreamOutputInvalidError
from npick_worker.jobs.transcripts import resolve_mapping
from npick_worker.vlm_metadata.grounding import TranscriptRef, TranscriptText
from npick_worker.vlm_metadata.models import SceneKeyframes


def attach_mapped_transcripts(
    scenes: Sequence[SceneKeyframes],
    upstream: Mapping[str, Any],
    documents: Mapping[str, Mapping[str, Any]],
) -> tuple[SceneKeyframes, ...]:
    """장면마다 채택된 대사를 근거와 함께 붙인다.

    매핑·snapshot 검증은 `resolve_mapping` 이 한다 — `text_embedding` 도 같은 판정을
    쓰므로 두 곳에서 따로 파싱하면 어느 날 둘이 갈라진다. 여기 남는 것은 VLM 고유의
    일뿐이다: 연결된 장면 집합이 keyframe 쪽과 같은지, 그리고 근거 참조 조립.
    """
    resolved = resolve_mapping(upstream, documents)
    if resolved is None:
        return tuple(scenes)
    mapping, linked = resolved
    try:
        segments_key = mapping.transcript.segments_artifact.storage_key
        by_scene = {scene.scene_index: scene.segments for scene in mapping.scenes}
        if set(by_scene) != {scene.scene_index for scene in scenes}:
            raise ValueError("mapped scenes must match frame extraction scenes")
        result: list[SceneKeyframes] = []
        for scene in scenes:
            texts: list[TranscriptText] = []
            for link in by_scene[scene.scene_index]:
                segment = linked[link.segment_id]
                texts.append(
                    TranscriptText(
                        ref=TranscriptRef(
                            scene_index=scene.scene_index,
                            storage_key=segments_key,
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
