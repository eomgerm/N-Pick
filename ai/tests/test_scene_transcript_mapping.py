"""Real mapping production, artifact round trip and existing VLM consumption."""

import hashlib
import json
from dataclasses import replace
from pathlib import Path
from typing import Any

import httpx2
import pytest

from npick_worker import korean_tokens
from npick_worker.jobs import registry
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import UpstreamOutputInvalidError
from npick_worker.jobs.media import MediaResolver
from npick_worker.jobs.models import WorkerDevice
from npick_worker.jobs.registry import StageContext
from npick_worker.jobs.runner import ClaimOutcome, JobRunner
from npick_worker.jobs.scene_transcript_mapping import run
from npick_worker.jobs.transcripts import transcript_refs
from npick_worker.jobs.vlm_inputs import attach_mapped_transcripts
from npick_worker.scene_transcript_mapping import Scene, SceneLinks, Segment, map_transcripts
from npick_worker.scene_transcript_mapping.mapper import ALGORITHM_VERSION
from npick_worker.vlm_metadata import KeyframeRef, SceneKeyframes

from .conftest import FakeBackend, make_job

STAGE = "scene_transcript_mapping"
PREFIX = f"runs/398021847361024/{STAGE}/a1/"


def segment(id_: str, s: int, e: int, source: str = "uploaded") -> dict[str, Any]:
    return {"segmentId": id_, "s": s, "e": e, "t": f"원문 {id_}", "sourceDetail": source}


def context(tmp_path: Path) -> StageContext:
    original = {
        "schemaVersion": "npick.transcript.segments/v1",
        "segments": [
            segment("u", 500, 1500),
            segment("cc", 1400, 2200, "embedded"),
            segment("cc2", 2500, 3000, "embedded"),
        ],
    }

    def ref(kind: str, body: dict[str, Any]) -> dict[str, Any]:
        data = json.dumps(body).encode()
        return {
            "kind": kind,
            "storageKey": f"runs/398021847361024/transcript_selection/a1/{kind}.json",
            "byteSize": len(data),
            "contentHash": hashlib.sha256(data).hexdigest(),
        }

    original_ref = ref("transcript_segments", original)
    decisions = {
        "schemaVersion": "npick.transcript.decisions/v1",
        "segmentsArtifact": original_ref,
        "decisions": [
            {
                "segmentId": "u",
                "selected": True,
                "reasonCode": "PREFERRED_SUBTITLE",
                "conflictsWith": [],
            },
            {
                "segmentId": "cc",
                "selected": False,
                "reasonCode": "OVERLAPS_HIGHER_PRIORITY",
                "conflictsWith": ["u"],
            },
            {
                "segmentId": "cc2",
                "selected": True,
                "reasonCode": "PREFERRED_SUBTITLE",
                "conflictsWith": [],
            },
        ],
    }
    decisions_ref = ref("transcript_decisions", decisions)
    upstream = {
        "sceneDetection": {
            "scenes": [
                {"sceneIndex": i, "startTimeMs": i * 1000, "endTimeMs": (i + 1) * 1000}
                for i in range(4)
            ]
        },
        "transcript": {"segmentsArtifact": original_ref, "decisionsArtifact": decisions_ref},
        "asr": {"segments": [], "reasonCode": "NO_SPEECH_DETECTED"},
    }
    return StageContext(
        stage=STAGE,
        video_path=None,
        storage_key="unused",
        work_dir=tmp_path,
        output_key_prefix=PREFIX,
        upstream=upstream,
        artifact_documents={
            original_ref["storageKey"]: original,
            decisions_ref["storageKey"]: decisions,
        },
    )


def test_half_open_boundaries_multiple_scenes_and_unlinked_original() -> None:
    result = map_transcripts(
        [Scene(0, 0, 1000), Scene(1, 1000, 2000), Scene(2, 2000, 3000)],
        [
            Segment("cross", 500, 1500, "가", "uploaded"),
            Segment("touch", 2000, 2500, "나", "uploaded"),
            Segment("outside", 3000, 4000, "다", "uploaded"),
        ],
    )
    assert result.scenes == (
        SceneLinks(0, (("cross", 500),)),
        SceneLinks(1, (("cross", 500),)),
        SceneLinks(2, (("touch", 500),)),
    )
    assert len(result.segments) == 3
    assert all(d.selected for d in result.decisions)


def test_excluded_cc_does_not_block_asr_in_subtitle_gap() -> None:
    segments = [
        Segment("u", 0, 100, "제공", "uploaded"),
        Segment("cc", 50, 200, "CC 전체", "embedded"),
        Segment("asr", 150, 300, "ASR 전체", "asr"),
        Segment("tail", 300, 400, "보완", "asr"),
    ]
    result = map_transcripts([Scene(0, 0, 500)], segments)
    assert result.scenes == (SceneLinks(0, (("u", 100), ("asr", 150), ("tail", 100))),)
    assert result.decisions[1].conflicts == ("u",)
    assert result.decisions[2].selected
    assert result.decisions[2].conflicts == ()
    assert list(result.segments) == segments
    assert map_transcripts([Scene(0, 0, 500)], list(reversed(segments))) == result


@pytest.mark.parametrize(
    "empty_asr", [None, {"segments": []}, {"segments": [], "reasonCode": "NO_SPEECH_DETECTED"}]
)
def test_empty_asr_preserves_subtitles_and_vlm_receives_only_selected_scene_text(
    tmp_path: Path,
    empty_asr: dict[str, Any] | None,
) -> None:
    ctx = context(tmp_path)
    upstream = dict(ctx.upstream)
    if empty_asr is None:
        upstream.pop("asr")
    else:
        upstream["asr"] = empty_asr
    outcome = run(replace(ctx, upstream=upstream))
    assert outcome.versions.stage_version == registry._declared_version(STAGE)
    assert outcome.versions.model_version is outcome.versions.prompt_version is None
    assert outcome.output["scenes"] == [
        {"sceneIndex": 0, "segments": [{"segmentId": "u", "overlapMs": 500}], "tokens": "원문 u"},
        {"sceneIndex": 1, "segments": [{"segmentId": "u", "overlapMs": 500}], "tokens": "원문 u"},
        {
            "sceneIndex": 2,
            "segments": [{"segmentId": "cc2", "overlapMs": 500}],
            "tokens": "원문 cc 2",
        },
        {"sceneIndex": 3, "segments": [], "tokens": ""},
    ]
    docs = {u.ref.storage_key: json.loads(u.local_path.read_bytes()) for u in outcome.uploads}
    frames = [SceneKeyframes(i, (KeyframeRef(i, i * 1000, f"f{i}"),)) for i in range(4)]
    attached = attach_mapped_transcripts(frames, {STAGE: outcome.output}, docs)
    assert [[t.t for t in s.transcripts] for s in attached] == [
        ["원문 u"],
        ["원문 u"],
        ["원문 cc2"],
        [],
    ]
    for upload in outcome.uploads:
        data = upload.local_path.read_bytes()
        assert len(data) == upload.ref.byte_size
        assert hashlib.sha256(data).hexdigest() == upload.ref.content_hash
    again = run(replace(ctx, upstream=upstream))
    assert again.output == outcome.output


def test_gap_asr_reaches_vlm_but_selected_cc_still_blocks_asr(tmp_path: Path) -> None:
    ctx = context(tmp_path)
    upstream = dict(ctx.upstream)
    upstream["asr"] = {
        "segments": [
            segment("gap", 1600, 1900, "asr"),  # overlaps only excluded cc
            segment("blocked", 2400, 2700, "asr"),  # selected cc2 starts later
        ]
    }
    outcome = run(replace(ctx, upstream=upstream))
    docs = {u.ref.storage_key: json.loads(u.local_path.read_bytes()) for u in outcome.uploads}
    decision_doc = docs[outcome.uploads[1].ref.storage_key]
    decisions = {d["segmentId"]: d for d in decision_doc["decisions"]}
    assert decisions["gap"] == {
        "segmentId": "gap",
        "selected": True,
        "reasonCode": "ASR_SUPPLEMENT",
        "conflictsWith": [],
    }
    assert not decisions["blocked"]["selected"]
    assert decisions["blocked"]["conflictsWith"] == ["cc2"]
    assert all(
        decisions[id_]["selected"]
        for decision in decisions.values()
        for id_ in decision["conflictsWith"]
    )
    frames = [SceneKeyframes(i, (KeyframeRef(i, i * 1000, f"f{i}"),)) for i in range(4)]
    attached = attach_mapped_transcripts(frames, {STAGE: outcome.output}, docs)
    assert [[t.t for t in s.transcripts] for s in attached] == [
        ["원문 u"],
        ["원문 u", "원문 gap"],
        ["원문 cc2"],
        [],
    ]


def test_asr_only_and_no_text(tmp_path: Path) -> None:
    ctx = context(tmp_path)
    upstream = {
        "sceneDetection": ctx.upstream["sceneDetection"],
        "asr": {"segments": [segment("a", 0, 500, "asr")]},
    }
    outcome = run(replace(ctx, upstream=upstream))
    assert outcome.output["scenes"][0]["segments"] == [{"segmentId": "a", "overlapMs": 500}]
    upstream["asr"] = {"segments": []}
    outcome = run(replace(ctx, upstream=upstream))
    assert all(s["segments"] == [] for s in outcome.output["scenes"])


def test_mixed_asr_keeps_full_rejected_original_and_adds_only_gap(tmp_path: Path) -> None:
    ctx = context(tmp_path)
    upstream = dict(ctx.upstream)
    upstream["asr"] = {
        "segments": [
            segment("overlap", 1000, 2500, "asr"),
            segment("gap", 3000, 3500, "asr"),
            segment("outside", 4500, 5000, "asr"),
        ]
    }
    outcome = run(replace(ctx, upstream=upstream))
    docs = [json.loads(u.local_path.read_bytes()) for u in outcome.uploads]
    originals = {s["segmentId"]: s for s in docs[0]["segments"]}
    assert originals["overlap"] == segment("overlap", 1000, 2500, "asr")
    decisions = {d["segmentId"]: d for d in docs[1]["decisions"]}
    assert decisions["overlap"]["conflictsWith"] == ["u"]
    assert decisions["gap"]["reasonCode"] == "ASR_SUPPLEMENT"
    assert decisions["outside"]["selected"] is True
    assert outcome.output["scenes"][3]["segments"] == [{"segmentId": "gap", "overlapMs": 500}]
    assert all(
        link["segmentId"] not in {"overlap", "outside"}
        for s in outcome.output["scenes"]
        for link in s["segments"]
    )


def test_duplicate_segment_and_empty_scene_list_are_rejected() -> None:
    original = Segment("same", 0, 100, "원문", "uploaded")
    with pytest.raises(ValueError):
        map_transcripts([Scene(0, 0, 100)], [original, original])
    with pytest.raises(ValueError):
        map_transcripts([], [])


def test_reverse_scene_order_has_identical_sorted_output() -> None:
    scenes = [Scene(2, 200, 300), Scene(0, 0, 100), Scene(1, 100, 200)]
    segments = [Segment("cross", 50, 250, "original", "uploaded")]
    result = map_transcripts(scenes, segments)
    assert [scene.index for scene in result.scenes] == [0, 1, 2]
    assert map_transcripts(list(reversed(scenes)), segments) == result


def test_mapping_recomputes_upstream_selection(tmp_path: Path) -> None:
    ctx = context(tmp_path)
    docs = dict(ctx.artifact_documents)
    decision_doc = docs[ctx.upstream["transcript"]["decisionsArtifact"]["storageKey"]]
    # Structurally valid upstream selection; final mapping must exclude cc again.
    decision_doc["decisions"][1].update(
        selected=True, reasonCode="PREFERRED_SUBTITLE", conflictsWith=[]
    )
    outcome = run(replace(ctx, artifact_documents=docs))
    decisions = json.loads(outcome.uploads[1].local_path.read_bytes())["decisions"]
    assert next(d for d in decisions if d["segmentId"] == "cc") == {
        "segmentId": "cc",
        "selected": False,
        "reasonCode": "OVERLAPS_HIGHER_PRIORITY",
        "conflictsWith": ["u"],
    }
    assert all(
        link["segmentId"] != "cc"
        for scene in outcome.output["scenes"]
        for link in scene["segments"]
    )


def test_typed_input_reports_contract_error_count(tmp_path: Path) -> None:
    ctx = context(tmp_path)
    with pytest.raises(UpstreamOutputInvalidError, match="상류 산출물이 계약과 다르다: 2건"):
        run(replace(ctx, upstream={"asr": {}}))
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize("field", ["sceneIndex", "startTimeMs", "endTimeMs"])
@pytest.mark.parametrize("value", [True, 1.5])
def test_shared_scene_model_rejects_non_integer_values(field: str, value: Any) -> None:
    from pydantic import ValidationError

    from npick_worker.jobs.models import SceneOut, UpstreamSceneOut

    payload = {"sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 100}
    payload[field] = value
    for model in (SceneOut, UpstreamSceneOut):
        with pytest.raises(ValidationError):
            model.model_validate(payload)


@pytest.mark.parametrize(
    "fault",
    [
        "timestamp",
        "float",
        "bool",
        "reversed",
        "blank",
        "duplicate_asr",
        "collision",
        "no_speech",
        "source",
        "missing_scene",
        "duplicate_scene",
        "bad_scene",
        "float_scene",
        "bool_scene",
        "empty_scene",
        "missing_decisions_artifact",
        "snapshot",
        "missing_document",
        "missing_decision",
        "unknown_decision",
        "missing_asr_segments",
    ],
)
def test_bad_input_is_validation_error_before_writing(tmp_path: Path, fault: str) -> None:
    ctx = context(tmp_path)
    upstream = dict(ctx.upstream)
    asr = {"segments": [segment("a", 0, 500, "asr")]}
    upstream["asr"] = asr
    scenes = upstream["sceneDetection"]["scenes"]
    docs = dict(ctx.artifact_documents)
    decision = docs[upstream["transcript"]["decisionsArtifact"]["storageKey"]]
    if fault == "timestamp":
        del asr["segments"][0]["s"]
    elif fault == "float":
        asr["segments"][0]["s"] = 0.5
    elif fault == "bool":
        asr["segments"][0]["s"] = False
    elif fault == "reversed":
        asr["segments"][0]["e"] = 0
    elif fault == "blank":
        asr["segments"][0]["t"] = " "
    elif fault == "duplicate_asr":
        asr["segments"].append(asr["segments"][0])
    elif fault == "collision":
        asr["segments"][0]["segmentId"] = "u"
    elif fault == "no_speech":
        upstream["asr"]["reasonCode"] = "NO_SPEECH_DETECTED"
    elif fault == "source":
        asr["segments"][0]["sourceDetail"] = "uploaded"
    elif fault == "missing_scene":
        upstream.pop("sceneDetection")
    elif fault == "duplicate_scene":
        scenes.append(scenes[0])
    elif fault == "bad_scene":
        scenes[0]["endTimeMs"] = 0
    elif fault == "float_scene":
        scenes[0]["startTimeMs"] = 0.1
    elif fault == "bool_scene":
        scenes[0]["startTimeMs"] = True
    elif fault == "empty_scene":
        scenes[1]["endTimeMs"] = scenes[1]["startTimeMs"]
    elif fault == "missing_decisions_artifact":
        del upstream["transcript"]["decisionsArtifact"]
    elif fault == "snapshot":
        decision["segmentsArtifact"]["contentHash"] = "0" * 64
        upstream["transcript"]["segmentsArtifact"] = dict(
            upstream["transcript"]["segmentsArtifact"], contentHash="1" * 64
        )
    elif fault == "missing_document":
        docs.clear()
    elif fault == "missing_decision":
        decision["decisions"].pop()
    elif fault == "unknown_decision":
        decision["decisions"][0]["segmentId"] = "unknown"
    elif fault == "missing_asr_segments":
        upstream["asr"] = {}
    with pytest.raises(UpstreamOutputInvalidError):
        run(replace(ctx, upstream=upstream, artifact_documents=docs))
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize("scene_key", ["sceneDetection", "scene_detection"])
@pytest.mark.asyncio
async def test_runner_uploads_produced_snapshots_then_completes(
    scene_key: str,
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    tmp_path: Path,
) -> None:
    ctx = context(tmp_path)
    upstream = dict(ctx.upstream)
    upstream[scene_key] = upstream.pop("sceneDetection")
    fake_backend.enqueue_claim(
        make_job(
            stage=STAGE,
            outputKeyPrefix=PREFIX,
            inputs={"media": {"storageKey": "unused"}, "upstream": upstream},
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
    assert len(fake_backend.calls("artifact_get")) == 2  # no source-video download
    assert len(fake_backend.calls("artifact_put")) == 2
    assert result["artifacts"] == list(result["output"]["transcript"].values())
    complete_position = fake_backend.requests.index(fake_backend.calls("complete")[0])
    assert all(
        fake_backend.requests.index(r) < complete_position
        for r in fake_backend.calls("artifact_put")
    )


def test_scene_tokens_cover_only_adopted_dialogue_and_declare_the_tokenizer(
    tmp_path: Path,
) -> None:
    """`scene.transcript_tokens` 가 될 값. BE 에 Kiwi 가 없어 워커가 만들어 보낸다."""
    ctx = context(tmp_path)
    upstream = dict(ctx.upstream)
    # `cc`(1400~2200)는 `u` 와 겹쳐 보관 전용이다. 그 원문이 토큰에 새면 검색이
    # 채택하지 않은 자막으로 장면을 찾아낸다.
    upstream["asr"] = {"segments": [segment("gap", 3000, 3500, "asr")]}
    outcome = run(replace(ctx, upstream=upstream))
    scenes = {s["sceneIndex"]: s for s in outcome.output["scenes"]}

    assert scenes[1]["tokens"] == "원문 u"
    assert "cc" not in scenes[1]["tokens"]
    assert scenes[3]["tokens"] == "원문 gap"
    # 대사가 없는 장면과 내용어가 없는 대사는 모두 빈 문자열이다 — 필드 누락이 아니다.
    empty = run(
        replace(
            ctx, upstream={"sceneDetection": ctx.upstream["sceneDetection"]}, artifact_documents={}
        )
    )
    assert [s["tokens"] for s in empty.output["scenes"]] == ["", "", "", ""]

    tokenizer = korean_tokens.tokenizer_version()
    assert outcome.versions.detail == {"algorithm": ALGORITHM_VERSION, "tokenizer": tokenizer}
    # 토크나이저가 재현 튜플에 있어야 토큰 경계가 바뀐 재처리를 구분할 수 있다.
    assert tokenizer in outcome.versions.stage_version or outcome.versions.stage_version.startswith(
        f"npick.stage.{STAGE}/v1:"
    )


def test_multiple_segments_in_one_scene_tokenize_in_link_order(tmp_path: Path) -> None:
    ctx = context(tmp_path)
    upstream = dict(ctx.upstream)
    upstream["sceneDetection"] = {
        "scenes": [{"sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 4000}]
    }
    upstream["asr"] = {"segments": [segment("gap", 3000, 3500, "asr")]}
    outcome = run(replace(ctx, upstream=upstream))
    scene = outcome.output["scenes"][0]

    assert [link["segmentId"] for link in scene["segments"]] == ["u", "cc2", "gap"]
    # 구간마다 따로 토큰화해 잇지 않고 연결 순서대로 한 문장으로 붙여 분석한다.
    assert scene["tokens"] == " ".join(korean_tokens.index_tokens("원문 u 원문 cc2 원문 gap"))


def test_camel_case_mapping_key_is_not_silently_treated_as_no_dialogue(tmp_path: Path) -> None:
    """키 표기가 어긋나면 하류가 실패 없이 대사 0건으로 돈다 — 그 자리를 막는다."""
    ctx = context(tmp_path)
    outcome = run(ctx)
    docs = {u.ref.storage_key: json.loads(u.local_path.read_bytes()) for u in outcome.uploads}
    frames = [SceneKeyframes(i, (KeyframeRef(i, i * 1000, f"f{i}"),)) for i in range(4)]

    snake = attach_mapped_transcripts(frames, {STAGE: outcome.output}, docs)
    camel = attach_mapped_transcripts(frames, {"sceneTranscriptMapping": outcome.output}, docs)
    assert [[t.t for t in s.transcripts] for s in camel] == [
        [t.t for t in s.transcripts] for s in snake
    ]
    assert any(s.transcripts for s in camel)
    assert transcript_refs({"sceneTranscriptMapping": outcome.output}, stage="vlm_metadata") == (
        transcript_refs({STAGE: outcome.output}, stage="vlm_metadata")
    )


def test_warm_up_actually_loads_kiwi_instead_of_returning_a_constant() -> None:
    """이 단계의 워밍업이 앞당기는 것은 Kiwi 초기화뿐이다 — 없으면 첫 잡이 그 값을 낸다."""
    korean_tokens._kiwi.cache_clear()
    report = registry.warm_up([STAGE])
    outcome = report.stages[0]

    assert outcome.warmed is True
    assert korean_tokens._kiwi.cache_info().currsize == 1
    assert ALGORITHM_VERSION in outcome.detail
    assert korean_tokens.tokenizer_version() in outcome.detail
