"""Preliminary adoption, ASR-need judgement and the snapshot the final stage reruns."""

import hashlib
import json
from dataclasses import replace
from pathlib import Path
from typing import Any

import httpx2
import pytest

from npick_worker.jobs import registry
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import InvalidTranscriptError, UpstreamOutputInvalidError
from npick_worker.jobs.media import MediaResolver
from npick_worker.jobs.models import WorkerDevice
from npick_worker.jobs.registry import StageContext
from npick_worker.jobs.runner import ClaimOutcome, JobRunner
from npick_worker.jobs.scene_transcript_mapping import run as run_mapping
from npick_worker.jobs.transcript_selection import run
from npick_worker.jobs.transcripts import (
    TranscriptDecisions,
    TranscriptSegments,
    validate_snapshot,
)
from npick_worker.scene_transcript_mapping import Scene, map_transcripts
from npick_worker.transcript_selection import (
    SELECTION_VERSION,
    Range,
    Segment,
    get_default_config,
    select,
    uncovered_ranges,
)

from .conftest import FakeBackend, make_job

STAGE = "transcript_selection"
RUN = "398021847361024"
PREFIX = f"runs/{RUN}/{STAGE}/a1/"
DURATION = 30_000


def segment(id_: str, s: int, e: int, source: str = "uploaded") -> dict[str, Any]:
    return {"segmentId": id_, "s": s, "e": e, "t": f"원문 {id_}", "sourceDetail": source}


def prepared(*segments: dict[str, Any]) -> dict[str, Any]:
    """BE 가 이번 시도에 준비해 둔 원본 자막 파일."""
    return {"schemaVersion": "npick.transcript.segments/v1", "segments": list(segments)}


def ref(body: dict[str, Any]) -> dict[str, Any]:
    data = json.dumps(body).encode()
    return {
        "kind": "transcript_segments",
        "storageKey": f"{PREFIX}transcript-segments-0000.json",
        "byteSize": len(data),
        "contentHash": hashlib.sha256(data).hexdigest(),
    }


def context(tmp_path: Path, *segments: dict[str, Any], duration: int = DURATION) -> StageContext:
    """4단계 하나를 실행할 컨텍스트. 하류 테스트가 재사용할 수 있게 모듈 레벨에 둔다."""
    original = prepared(*segments)
    original_ref = ref(original)
    return StageContext(
        stage=STAGE,
        video_path=None,
        storage_key="unused",
        work_dir=tmp_path,
        output_key_prefix=PREFIX,
        upstream={
            "sceneDetection": {"mediaDurationMs": duration},
            "transcript": {"segmentsArtifact": original_ref},
        },
        artifact_documents={original_ref["storageKey"]: original},
    )


def default_context(tmp_path: Path) -> StageContext:
    """자막과 CC 가 겹치고 뒤쪽이 비어 있는, 이 단계의 대표 입력."""
    return context(
        tmp_path,
        segment("u", 500, 1500),
        segment("cc", 1400, 2200, "embedded"),
        segment("cc2", 2500, 3000, "embedded"),
    )


# ── 순수 계산: 커버리지 ────────────────────────────────────────────────


def test_no_subtitle_leaves_the_whole_clip_as_one_candidate() -> None:
    # 특례 분기가 아니라 여집합의 결과다. 클립 길이가 항상 양수이기 때문이다.
    assert uncovered_ranges(DURATION, []) == (Range(0, DURATION),)


def test_full_coverage_leaves_no_candidate() -> None:
    assert uncovered_ranges(DURATION, [(0, DURATION)]) == ()


def test_touching_subtitles_leave_no_gap_between_them() -> None:
    assert uncovered_ranges(3000, [(0, 1000), (1000, 3000)]) == ()


def test_gaps_appear_between_and_after_coverage() -> None:
    assert uncovered_ranges(5000, [(0, 1000), (2000, 3000)]) == (
        Range(1000, 2000),
        Range(3000, 5000),
    )


def test_coverage_past_the_clip_end_is_clamped_not_rejected() -> None:
    """자막 시간축과 `mediaDurationMs` 는 서로 다른 측정값이다(ffprobe 초 vs 프레임 수).

    클램프가 없으면 음수 시작이나 길이 0 이하 구간이 나와 BE 가 `e > s` 에서 거절한다.
    """
    assert uncovered_ranges(5000, [(0, 9000)]) == ()
    assert uncovered_ranges(5000, [(4000, 9000)]) == (Range(0, 4000),)


def test_overlapping_coverage_merges_before_complementing() -> None:
    assert uncovered_ranges(5000, [(0, 2000), (1500, 3000)]) == (Range(3000, 5000),)


def test_coverage_order_does_not_change_the_result() -> None:
    forward = uncovered_ranges(5000, [(0, 1000), (2000, 3000)])
    assert uncovered_ranges(5000, [(2000, 3000), (0, 1000)]) == forward


def test_minimum_length_drops_short_gaps() -> None:
    adopted = [(0, 1000), (1040, 5000)]
    assert uncovered_ranges(5000, adopted) == (Range(1000, 1040),)
    assert uncovered_ranges(5000, adopted, min_length_ms=100) == ()


@pytest.mark.parametrize("duration", [0, -1, 1.0, True])
def test_coverage_requires_a_positive_integer_duration(duration: object) -> None:
    with pytest.raises(ValueError, match="positive integer clip duration"):
        uncovered_ranges(duration, [])  # type: ignore[arg-type]


# ── 순수 계산: 채택 규칙 ───────────────────────────────────────────────


def test_provided_subtitle_wins_over_overlapping_cc() -> None:
    result = select(
        [Segment("u", 0, 1000, "a", "uploaded"), Segment("cc", 500, 1500, "b", "embedded")]
    )
    assert result.selected == frozenset({"u"})
    assert [(d.segment_id, d.selected, d.conflicts) for d in result.decisions] == [
        ("u", True, ()),
        ("cc", False, ("u",)),
    ]


def test_lowest_priority_source_is_always_adopted() -> None:
    """구간은 있으나 전부 제외되는 상태는 도달 불가다.

    제외는 채택된 상위 출처와 겹칠 때만 일어나므로, 우선순위가 가장 높은 구간들은
    자기보다 높은 출처가 없어 반드시 채택된다. 그래서 원본이 1건 이상이면 채택도
    1건 이상이고, `NO_VALID_SUBTITLE` 은 원본 0건만을 뜻한다.
    """
    result = select([Segment("cc", 0, 1000, "a", "embedded")])
    assert result.selected == frozenset({"cc"})


def test_selection_order_does_not_change_the_decisions() -> None:
    segments = [
        Segment("u", 500, 1500, "a", "uploaded"),
        Segment("cc", 1400, 2200, "b", "embedded"),
    ]
    assert select(segments).decisions == select(list(reversed(segments))).decisions


def test_duplicate_segment_ids_are_rejected() -> None:
    with pytest.raises(ValueError, match="duplicate segment ID"):
        select([Segment("x", 0, 1000, "a", "uploaded"), Segment("x", 0, 1000, "b", "embedded")])


def test_both_stages_share_one_adoption_rule() -> None:
    """계약 §4.5 는 두 단계가 같은 채택 규칙을 따르도록 요구한다.

    사람 기억에 맡기지 않는다 — 어긋나면 대사가 사라지지 않고 CC 채택 여부만 달라져
    같은 run 안에서 두 snapshot 이 조용히 갈린다. 규칙 한 벌을 쓰는 사실을 여기서
    기계로 고정한다.
    """
    segments = [
        Segment("u", 500, 1500, "a", "uploaded"),
        Segment("cc", 1400, 2200, "b", "embedded"),
        Segment("cc2", 2500, 3000, "c", "embedded"),
    ]
    mapped = map_transcripts([Scene(0, 0, 3000)], segments)
    assert select(segments).decisions == mapped.decisions


# ── 어댑터 ─────────────────────────────────────────────────────────────


def test_selection_reports_uncovered_ranges_and_requires_asr(tmp_path: Path) -> None:
    outcome = run(default_context(tmp_path))
    assert outcome.output["transcript"]["asrRequired"] is True
    assert outcome.output["transcript"]["reasonCode"] == "UNCOVERED_RANGES"
    # 채택된 것은 u(500-1500)·cc2(2500-3000) 뿐이다. cc 는 u 와 겹쳐 제외된다.
    assert outcome.output["transcript"]["candidateRanges"] == [
        {"s": 0, "e": 500},
        {"s": 1500, "e": 2500},
        {"s": 3000, "e": DURATION},
    ]


def test_judgement_lives_inside_transcript_where_the_backend_reads_it(tmp_path: Path) -> None:
    """BE 는 세 칸을 `output.transcript` 안에서 읽는다.

    `StageExecutionService` 의 complete 검사가 그 객체를 열어
    `asrRequired`·`candidateRanges`·`reasonCode` 를 찾고
    `JdbcWorkerStageOutputAdapter.validateTranscript` 도 같은 객체를 받는다. 형제로
    올리면 값이 맞아도 세 칸이 모두 없는 것으로 읽혀 `INVALID_RESULT` 다.
    """
    output = run(default_context(tmp_path)).output
    assert set(output) == {"transcript"}
    assert set(output["transcript"]) == {
        "segmentsArtifact",
        "decisionsArtifact",
        "asrRequired",
        "candidateRanges",
        "reasonCode",
    }


def test_full_subtitle_coverage_lets_be_skip_asr(tmp_path: Path) -> None:
    outcome = run(context(tmp_path, segment("u", 0, DURATION)))
    assert outcome.output["transcript"]["asrRequired"] is False
    assert outcome.output["transcript"]["reasonCode"] == "SUBTITLE_COVERED"
    assert outcome.output["transcript"]["candidateRanges"] == []


def test_no_subtitle_at_all_still_carries_one_candidate(tmp_path: Path) -> None:
    # BE 는 asrRequired=true 인데 후보가 비어 있으면 결과를 거절한다.
    outcome = run(context(tmp_path))
    assert outcome.output["transcript"]["reasonCode"] == "NO_VALID_SUBTITLE"
    assert outcome.output["transcript"]["asrRequired"] is True
    assert outcome.output["transcript"]["candidateRanges"] == [{"s": 0, "e": DURATION}]


def test_excluded_cc_is_preserved_whole_with_overlap_evidence(tmp_path: Path) -> None:
    outcome = run(default_context(tmp_path))
    docs = {u.ref.storage_key: json.loads(u.local_path.read_bytes()) for u in outcome.uploads}
    segments = TranscriptSegments.model_validate(docs[outcome.uploads[0].ref.storage_key])
    decisions = TranscriptDecisions.model_validate(docs[outcome.uploads[1].ref.storage_key])
    # 원문 전체가 남는다. 시간만 잘라 부분 발화로 만들지 않는다.
    assert [s.segment_id for s in segments.segments] == ["u", "cc", "cc2"]
    assert {s.segment_id: (s.s, s.e) for s in segments.segments}["cc"] == (1400, 2200)
    assert [
        (d.segment_id, d.selected, d.reason_code, d.conflicts_with) for d in decisions.decisions
    ] == [
        ("u", True, "PREFERRED_SUBTITLE", []),
        ("cc", False, "OVERLAPS_HIGHER_PRIORITY", ["u"]),
        ("cc2", True, "PREFERRED_SUBTITLE", []),
    ]


def test_uploaded_snapshot_matches_its_reference_and_passes_the_shared_validator(
    tmp_path: Path,
) -> None:
    outcome = run(default_context(tmp_path))
    for upload in outcome.uploads:
        data = upload.local_path.read_bytes()
        assert len(data) == upload.ref.byte_size
        assert hashlib.sha256(data).hexdigest() == upload.ref.content_hash
        assert upload.ref.storage_key.startswith(PREFIX)
    segments = TranscriptSegments.model_validate(
        json.loads(outcome.uploads[0].local_path.read_bytes())
    )
    decisions = TranscriptDecisions.model_validate(
        json.loads(outcome.uploads[1].local_path.read_bytes())
    )
    # 6단계가 이 파일을 읽을 때 쓰는 것과 같은 검사다.
    validate_snapshot(outcome.uploads[0].ref, segments, decisions)


def test_originals_survive_verbatim_for_the_backend_preservation_check(tmp_path: Path) -> None:
    """BE 는 준비 파일을 다시 읽어 ID 집합과 각 구간이 동일한지 본다(계약 §4.5)."""
    ctx = default_context(tmp_path)
    original = next(iter(ctx.artifact_documents.values()))
    outcome = run(ctx)
    written = json.loads(outcome.uploads[0].local_path.read_bytes())
    assert written["segments"] == original["segments"]
    assert written["schemaVersion"] == original["schemaVersion"]


def test_preliminary_snapshot_feeds_the_final_stage_without_conflict(tmp_path: Path) -> None:
    """4단계의 decisions 는 예비 판정이고 6단계 snapshot 이 그 run 의 최종 정본이다.

    두 단계가 같은 규칙을 쓰므로 예비 판정을 그대로 먹여도 6단계가 거절하지 않는다.
    """
    ctx = default_context(tmp_path)
    outcome = run(ctx)
    docs = {u.ref.storage_key: json.loads(u.local_path.read_bytes()) for u in outcome.uploads}
    mapping_dir = tmp_path / "mapping"
    mapping_dir.mkdir()
    final = run_mapping(
        replace(
            ctx,
            stage="scene_transcript_mapping",
            work_dir=mapping_dir,
            output_key_prefix=f"runs/{RUN}/scene_transcript_mapping/a1/",
            upstream={
                "sceneDetection": {
                    "scenes": [{"sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 3000}]
                },
                "transcript": outcome.output["transcript"],
            },
            artifact_documents=docs,
        )
    )
    assert [link["segmentId"] for link in final.output["scenes"][0]["segments"]] == ["u", "cc2"]


def test_versions_declare_the_shared_rule_and_the_config(tmp_path: Path) -> None:
    outcome = run(default_context(tmp_path))
    versions = outcome.versions
    assert versions.stage_version == registry._declared_version(STAGE)
    assert versions.output_schema_version == f"npick.stage.{STAGE}.output/v1"
    assert versions.config_version == get_default_config().version_id
    # 모델도 프롬프트도 쓰지 않는다. 키는 남기고 값만 비운다.
    assert versions.model_version is None
    assert versions.prompt_version is None
    # 6단계와 같은 키로 같은 값을 싣는다 — 두 detail 을 나란히 놓고 규칙 일치를 본다.
    assert versions.detail == {"selection": SELECTION_VERSION}


def test_running_twice_produces_the_same_output(tmp_path: Path) -> None:
    first = run(default_context(tmp_path))
    second = run(default_context(tmp_path))
    assert first.output == second.output
    assert [u.ref for u in first.uploads] == [u.ref for u in second.uploads]


def test_metrics_carry_the_judgement(tmp_path: Path) -> None:
    outcome = run(default_context(tmp_path))
    assert outcome.metrics == {
        "segments": 3,
        "selected": 2,
        "candidateRanges": 3,
        "asrRequired": True,
    }


def test_asr_segments_in_the_prepared_snapshot_are_a_permanent_failure(tmp_path: Path) -> None:
    """4단계 시점에 ASR 은 돌지 않았고 BE 는 uploaded·embedded 만 만든다.

    그것이 왔다면 배정이나 준비가 어긋난 것이다. 그대로 예비 판정을 내면 인식 없는
    ASR_SUPPLEMENT 채택이 최종 정본으로 되살아날 수 있다.
    """
    ctx = context(tmp_path, segment("a", 0, 1000, "asr"))
    with pytest.raises(InvalidTranscriptError, match="ASR segments"):
        run(ctx)
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize(
    "fault",
    [
        "no_transcript",
        "no_scene_detection",
        "zero_duration",
        "missing_document",
        "schema_version",
        "duplicate_id",
        "reversed_interval",
        "float_timestamp",
        "blank_text",
        "unknown_source",
        "extra_segment_field",
    ],
)
def test_broken_upstream_never_writes_a_file(tmp_path: Path, fault: str) -> None:
    ctx = default_context(tmp_path)
    upstream = dict(ctx.upstream)
    docs = dict(ctx.artifact_documents)
    key = next(iter(docs))
    if fault == "no_transcript":
        del upstream["transcript"]
    elif fault == "no_scene_detection":
        del upstream["sceneDetection"]
    elif fault == "zero_duration":
        upstream["sceneDetection"] = {"mediaDurationMs": 0}
    elif fault == "missing_document":
        docs = {}
    elif fault == "schema_version":
        docs[key] = {**docs[key], "schemaVersion": "npick.transcript.segments/v2"}
    elif fault == "duplicate_id":
        docs[key] = prepared(segment("u", 0, 1000), segment("u", 2000, 3000))
    elif fault == "reversed_interval":
        docs[key] = prepared(segment("u", 1500, 500))
    elif fault == "float_timestamp":
        docs[key] = prepared({**segment("u", 0, 1000), "s": 0.5})
    elif fault == "blank_text":
        docs[key] = prepared({**segment("u", 0, 1000), "t": "   "})
    elif fault == "unknown_source":
        docs[key] = prepared(segment("u", 0, 1000, "script"))
    elif fault == "extra_segment_field":
        # BE 가 필드를 추가하면 조용히 떨어지지 않고 터져야 한다 — 그 필드를 버린
        # snapshot 은 BE 의 원본 보존 검사에서 거절된다.
        docs[key] = prepared({**segment("u", 0, 1000), "speaker": "앵커"})
    with pytest.raises(UpstreamOutputInvalidError):
        run(replace(ctx, upstream=upstream, artifact_documents=docs))
    assert not list(tmp_path.iterdir())


@pytest.mark.asyncio
async def test_runner_uploads_both_snapshots_then_completes(
    job_client: JobApiClient, fake_backend: FakeBackend, tmp_path: Path
) -> None:
    ctx = default_context(tmp_path)
    fake_backend.enqueue_claim(
        make_job(
            stage=STAGE,
            outputKeyPrefix=PREFIX,
            inputs={"media": {"storageKey": "unused"}, "upstream": ctx.upstream},
        )
    )
    for doc in ctx.artifact_documents.values():
        fake_backend.enqueue("artifact_get", httpx2.Response(200, content=json.dumps(doc).encode()))
    fake_backend.enqueue("artifact_put", httpx2.Response(201))
    fake_backend.enqueue("artifact_put", httpx2.Response(201))
    runner = JobRunner(
        client=job_client,
        media=MediaResolver(None, job_client),
        worker_id=job_client.worker_id,
        fleet="local",
        poll_wait_seconds=0,
        heartbeat_seconds=30,
        shared_media_volume=False,
        media_root=None,
        device=WorkerDevice(kind="cpu"),
    )

    assert await runner.run_once() is ClaimOutcome.SUCCEEDED

    result = json.loads(fake_backend.calls("complete")[0].content)
    assert result["status"] == "succeeded"
    # 원본 영상을 받지 않았다. 준비된 자막 문서 한 장만 받는다.
    assert len(fake_backend.calls("artifact_get")) == 1
    assert len(fake_backend.calls("artifact_put")) == 2
    # 봉투의 참조와 output 의 참조가 같아야 BE 가 받는다. `transcript` 를 통째로
    # 펼치지 않는다 — 그 객체에는 판정 세 칸이 함께 살고 참조가 아니다.
    transcript = result["output"]["transcript"]
    assert result["artifacts"] == [
        transcript["segmentsArtifact"],
        transcript["decisionsArtifact"],
    ]
    # 업로드가 끝난 뒤에 complete 가 간다. 순서가 뒤집히면 BE 가 없는 파일을 검증한다.
    complete_position = fake_backend.requests.index(fake_backend.calls("complete")[0])
    assert all(
        fake_backend.requests.index(r) < complete_position
        for r in fake_backend.calls("artifact_put")
    )


# ── 길이 축 불일치 보정 (S15P21A501-215) ─────────────────────────────


def test_default_config_drops_the_duration_axis_artifact() -> None:
    """꼬리에 남는 1ms 틈이 전체 ASR 을 부르지 않게 한다.

    길이 축이 둘이다. BE 는 자막을 접수할 때 ffprobe `format.duration` 으로 상한을
    검사하고(`SubtitleParser`), 이 단계는 프레임 수 기반 `mediaDurationMs` 로 여집합을
    구한다(`scene_detection/pyscenedetect_backend.py` 의 `frames_to_ms`). 두 값이 달라
    전 구간을 덮는 자막을 써도 꼬리에 정수 ms 틈이 남는다.

    **실측으로 상한이 나온다** — b-roll 60클립에서 두 축의 차이는 최대 0.500ms 였고
    그건 `frames_to_ms` 의 반올림 오차뿐이다(프레임 한 장 33.4ms 와 무관). 자막 종료
    시각은 ffprobe 길이 이하의 정수 ms 이므로, 두 축이 만드는 꼬리 틈은 정수로 최대
    1ms 다. 그래서 2 가 이 인공물을 지우는 **가장 작은** 값이다.
    """
    assert get_default_config().min_uncovered_ms == 2


def test_one_ms_tail_gap_is_not_a_candidate() -> None:
    """KNA_02701 에서 실제로 나온 모양이다.

    영상 15181.833ms, 프레임 기반 15182ms. 자막을 15181ms 까지 덮었더니 [15181, 15182)
    한 칸이 남아 `asrRequired=true` 가 됐고 ASR 이 **영상 전체**에 돌았다 — ASR 은
    `candidateRanges` 를 구간 제한에 쓰지 않는다.
    """
    adopted = [(0, 15181)]
    assert uncovered_ranges(15182, adopted, min_length_ms=2) == ()


def test_a_real_gap_survives_the_floor() -> None:
    """보정은 인공물만 지운다. 발화가 들어갈 틈은 그대로 후보다."""
    adopted = [(0, 5000), (5002, 10000)]
    assert uncovered_ranges(10000, adopted, min_length_ms=2) == (Range(5000, 5002),)
