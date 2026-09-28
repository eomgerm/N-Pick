"""Adapt final transcript mapping to the existing artifact and VLM contracts."""

from collections.abc import Mapping
from typing import Any, Literal

from pydantic import Field

from npick_worker.jobs.errors import UpstreamOutputInvalidError
from npick_worker.jobs.models import UpstreamSceneOut, WireResponse
from npick_worker.jobs.registry import PendingUpload, StageContext, StageOutcome, _parse_upstream
from npick_worker.jobs.transcripts import (
    MappedSegment,
    SceneTranscriptLinks,
    SceneTranscriptMappingOutput,
    TranscriptDecision,
    TranscriptDecisions,
    TranscriptSegment,
    TranscriptSegments,
    TranscriptSnapshot,
    UpstreamTranscriptSnapshot,
    json_artifact,
    validate_snapshot,
)
from npick_worker.jobs.versions import StageVersion, output_schema_version, stage_version
from npick_worker.korean_tokens import index_tokens
from npick_worker.scene_transcript_mapping import Scene, Segment, map_transcripts
from npick_worker.scene_transcript_mapping.mapper import ALGORITHM_VERSION
from npick_worker.transcript_selection import SELECTION_VERSION


class _Scenes(WireResponse):
    # Mapping needs only intervals, not the duration/frame rate used by frame extraction.
    scenes: list[UpstreamSceneOut] = Field(min_length=1)


class _AsrSegment(TranscriptSegment):
    model_config = WireResponse.model_config
    source_detail: Literal["asr"]


class _Asr(WireResponse):
    segments: list[_AsrSegment]
    reason_code: Literal["NO_SPEECH_DETECTED"] | None = None


class SceneTranscriptMappingUpstream(WireResponse):
    # Keep transcript-dependent models here: transcripts already imports jobs.models.
    scene_detection: _Scenes
    transcript: UpstreamTranscriptSnapshot | None = None
    asr: _Asr | None = None


def identity() -> dict[str, str]:
    """재현 튜플. 축이 셋이다.

    `tokenizer` 가 있는 이유는 `ocr`·`vlm_metadata` 와 같다 — 이 단계가 만드는
    `scenes[].tokens` 가 곧 `scene.transcript_tokens` 이고, 그 토큰 경계가 바뀌면
    같은 대사에서 다른 색인이 나온다. `asr` 이 이 축을 두지 않은 것과 짝이다
    (그 docstring: "대사의 토큰화는 채택된 구간을 다루는 하류의 일").

    `selection` 은 이 단계가 소유하지 않는 축이다. 채택 규칙은 4단계 패키지에 있고
    (계약 §4.5 가 두 단계에 같은 규칙을 요구한다) 그것이 바뀌면 장면 연결 로직이
    그대로여도 이 단계의 판정이 바뀐다. 축을 두지 않으면 다른 규칙으로 만든 결과가
    같은 재현 식별자를 갖는다. **4단계와 같은 키 이름을 쓴다** — 두 단계의 `detail`
    을 눈으로 대조해 규칙 일치를 확인할 수 있어야 한다.
    """
    from npick_worker import korean_tokens

    return {
        "algorithm": ALGORITHM_VERSION,
        "selection": SELECTION_VERSION,
        "tokenizer": korean_tokens.tokenizer_version(),
    }


def _upload(ctx: StageContext, kind: str, payload: Mapping[str, Any]) -> PendingUpload:
    ref, path = json_artifact(ctx.work_dir, ctx.output_key_prefix, kind, payload)
    return PendingUpload(ref=ref, local_path=path, content_type="application/json")


def run(ctx: StageContext) -> StageOutcome:
    upstream = _parse_upstream(SceneTranscriptMappingUpstream, ctx.upstream)
    try:
        scene_input = upstream.scene_detection
        originals: list[TranscriptSegment] = []
        if upstream.transcript is not None:
            snapshot = TranscriptSnapshot.model_validate(
                upstream.transcript.model_dump(by_alias=True)
            )
            # The runner validates downloads too. Revalidate at this adapter boundary
            # because direct callers can supply artifact_documents without the runner.
            original = TranscriptSegments.model_validate(
                ctx.artifact_documents[snapshot.segments_artifact.storage_key]
            )
            decisions = TranscriptDecisions.model_validate(
                ctx.artifact_documents[snapshot.decisions_artifact.storage_key]
            )
            validate_snapshot(snapshot.segments_artifact, original, decisions)
            originals.extend(original.segments)
        if upstream.asr is not None:
            asr = upstream.asr
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
    texts = {s.id: s.text for s in result.segments}
    output = SceneTranscriptMappingOutput(
        transcript=TranscriptSnapshot(
            segments_artifact=segment_upload.ref, decisions_artifact=decision_upload.ref
        ),
        scenes=[
            SceneTranscriptLinks(
                scene_index=scene.index,
                segments=[MappedSegment(segment_id=id_, overlap_ms=ms) for id_, ms in scene.links],
                # 연결 순서(시간순)대로 원문을 이어 한 번에 토큰화한다. 구간마다 따로
                # 돌려 이으면 경계에서 형태소 분석이 문맥을 잃는다. BE 는 같은 순서로
                # `transcript_text` 를 만들므로 두 컬럼이 같은 문장을 가리킨다.
                tokens=" ".join(index_tokens(" ".join(texts[id_] for id_, _ in scene.links))),
            )
            for scene in result.scenes
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
