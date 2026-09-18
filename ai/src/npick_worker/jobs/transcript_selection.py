"""Adapt preliminary transcript selection to the existing artifact contract.

순수 모듈은 잡 API 를 모른다. `inputs.upstream` 을 그 dataclass 로 바꾸는 일이 전부
여기 있다 — `ai/AGENTS.md` 가 긋는 경계이고 `jobs/scene_transcript_mapping.py` 가
이미 따르는 형태다.
"""

from typing import Any

from pydantic import Field, StrictInt

from npick_worker.jobs.errors import InvalidTranscriptError, UpstreamOutputInvalidError
from npick_worker.jobs.models import WireResponse
from npick_worker.jobs.registry import (
    PendingUpload,
    StageContext,
    StageOutcome,
    _parse_upstream,
    _runtime,
)
from npick_worker.jobs.transcripts import (
    TranscriptCandidateRange,
    TranscriptDecision,
    TranscriptDecisions,
    TranscriptSegments,
    TranscriptSelectionOutput,
    TranscriptSnapshot,
    json_artifact,
    validate_snapshot,
)
from npick_worker.jobs.versions import StageVersion, output_schema_version, stage_version
from npick_worker.transcript_selection import (
    SELECTION_VERSION,
    Segment,
    get_default_config,
    select,
    uncovered_ranges,
)


class _PreparedTranscript(WireResponse):
    """BE 가 이번 시도에 준비해 붙인 원본 자막.

    `decisionsArtifact` 를 두지 않는다 — 판정은 이 단계가 처음 만든다. `segments_artifact`
    만 선언하고 나머지(`embeddedInspection` 등)는 `extra="ignore"` 로 흘려보낸다:
    그중 `selectedStreamIndex` 는 CC 트랙이 없으면 null 이라, 필수 정수로 선언하면 CC
    없는 클립마다 이 단계가 영구 실패한다.
    """

    segments_artifact: dict[str, Any]


class _MediaDuration(WireResponse):
    """1단계 산출물에서 클립 길이만. `UpstreamSceneDetection` 을 쓰지 않는 이유는
    그것이 `scenes`(min_length=1)·`frameRate` 를 필수로 끌고 오기 때문이다 — 이 단계는
    장면을 보지 않는다."""

    media_duration_ms: StrictInt = Field(gt=0)


class TranscriptSelectionUpstream(WireResponse):
    scene_detection: _MediaDuration
    transcript: _PreparedTranscript


def identity() -> dict[str, str]:
    """재현 튜플. 축이 둘이다.

    `selection` 은 채택 규칙의 식별자이고 **6단계가 같은 키로 같은 값을 싣는다** —
    계약 §4.5 가 두 단계에 같은 규칙을 요구하므로 두 `detail` 을 나란히 놓고 확인할
    수 있어야 한다. `configVersion` 은 `min_uncovered_ms` 를 접는다: 그 값이 바뀌면
    같은 자막에서 다른 `candidateRanges` 가 나온다.

    `tokenizer` 축은 없다. 이 단계는 색인 토큰을 만들지 않는다 — 대사의 토큰화는
    채택된 구간을 다루는 하류의 일이다(`asr` 과 같은 판단).
    """
    return {"selection": SELECTION_VERSION, "configVersion": get_default_config().version_id}


def run(ctx: StageContext) -> StageOutcome:
    config = get_default_config()
    upstream = _parse_upstream(TranscriptSelectionUpstream, ctx.upstream)
    duration = upstream.scene_detection.media_duration_ms
    try:
        original = TranscriptSegments.model_validate(
            ctx.artifact_documents[upstream.transcript.segments_artifact["storageKey"]]
        )
    except (ValueError, KeyError, TypeError) as exc:
        raise UpstreamOutputInvalidError("invalid prepared transcript snapshot") from exc

    if any(segment.source_detail == "asr" for segment in original.segments):
        # 공용 검증기가 통과시키지만 이 단계만 아는 사실이다. errors.py 의 docstring 참고.
        raise InvalidTranscriptError("prepared transcript contains ASR segments")

    try:
        selection = select(
            [Segment(s.segment_id, s.s, s.e, s.t, s.source_detail) for s in original.segments]
        )
    except (ValueError, TypeError) as exc:
        raise UpstreamOutputInvalidError("invalid prepared transcript segments") from exc

    sources = {s.segment_id: s for s in original.segments}
    ranges = uncovered_ranges(
        duration,
        (
            (sources[id_].s, sources[id_].e)
            for id_ in selection.selected
            # ASR 은 커버리지가 아니다. 지금은 위에서 걸러지므로 도달하지 않지만,
            # 이 계산이 "자막·CC 가 덮은 구간" 이라는 사실을 코드에 남긴다.
            if sources[id_].source_detail != "asr"
        ),
        min_length_ms=config.min_uncovered_ms,
    )

    # **원본을 그대로 다시 쓴다.** BE 는 준비 파일을 다시 읽어 ID 집합이 같고 각 구간이
    # 동일한지 검사한다(계약 §4.5). 구간을 고치거나 버리면 그 자리에서 거절된다.
    segments_ref, segments_path = json_artifact(
        ctx.work_dir,
        ctx.output_key_prefix,
        "transcript_segments",
        original.model_dump(by_alias=True),
    )
    segment_upload = PendingUpload(
        ref=segments_ref, local_path=segments_path, content_type="application/json"
    )
    decisions = TranscriptDecisions(
        schema_version="npick.transcript.decisions/v1",
        segments_artifact=segments_ref,
        decisions=[
            TranscriptDecision(
                segment_id=d.segment_id,
                selected=d.selected,
                reason_code=(
                    "OVERLAPS_HIGHER_PRIORITY"
                    if not d.selected
                    else "ASR_SUPPLEMENT"
                    if sources[d.segment_id].source_detail == "asr"
                    else "PREFERRED_SUBTITLE"
                ),
                conflicts_with=list(d.conflicts),
            )
            for d in selection.decisions
        ],
    )
    # 올리기 전에 내 산출물을 공용 검증기로 다시 본다. 6단계가 이 파일을 읽을 때 쓰는
    # 것과 같은 검사이므로, 어긋나면 하류가 아니라 여기서 드러난다.
    validate_snapshot(segments_ref, original, decisions)
    decisions_ref, decisions_path = json_artifact(
        ctx.work_dir,
        ctx.output_key_prefix,
        "transcript_decisions",
        decisions.model_dump(by_alias=True),
    )
    decision_upload = PendingUpload(
        ref=decisions_ref, local_path=decisions_path, content_type="application/json"
    )

    output = TranscriptSelectionOutput(
        transcript=TranscriptSnapshot(
            segments_artifact=segments_ref, decisions_artifact=decisions_ref
        ),
        asr_required=bool(ranges),
        candidate_ranges=[TranscriptCandidateRange(s=r.s, e=r.e) for r in ranges],
        reason_code=(
            "SUBTITLE_COVERED"
            if not ranges
            else "NO_VALID_SUBTITLE"
            if not original.segments
            else "UNCOVERED_RANGES"
        ),
    )
    return StageOutcome(
        output=output.model_dump(by_alias=True),
        versions=StageVersion(
            stage_version=stage_version(ctx.stage, identity()),
            output_schema_version=output_schema_version(ctx.stage),
            config_version=config.version_id,
            # 이 단계는 가중치도 프롬프트도 쓰지 않는다. 키는 남기고 값만 비운다.
            model_version=None,
            prompt_version=None,
            detail={"selection": SELECTION_VERSION},
            runtime=_runtime(),
        ),
        uploads=(segment_upload, decision_upload),
        metrics={
            "segments": len(original.segments),
            "selected": len(selection.selected),
            "candidateRanges": len(ranges),
            "asrRequired": bool(ranges),
        },
    )
