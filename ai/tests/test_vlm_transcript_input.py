"""합의한 장면 연결 계약을 소비한다. 매핑 알고리즘 대신 정해진 연결 샘플을 사용한다."""

import hashlib
import json
from pathlib import Path
from typing import Any

import httpx2
import pytest

from npick_worker.jobs import registry
from npick_worker.jobs.artifacts import resolve_transcripts
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import UpstreamOutputInvalidError
from npick_worker.jobs.media import MediaResolver
from npick_worker.jobs.models import JobAssignment
from npick_worker.jobs.transcripts import transcript_refs
from npick_worker.jobs.vlm_inputs import attach_mapped_transcripts
from npick_worker.vlm_metadata import KeyframeRef, SceneKeyframes

from .conftest import FakeBackend, make_job
from .test_vlm_grounding import RecordingClient


def sample() -> tuple[dict[str, Any], dict[str, Any]]:
    original = {
        "schemaVersion": "npick.transcript.segments/v1",
        "segments": [
            {
                "segmentId": "s1",
                "s": 0,
                "e": 3000,
                "t": "부산 축제 소개",
                "sourceDetail": "uploaded",
            },
            {"segmentId": "s2", "s": 0, "e": 3000, "t": "제외된 ASR", "sourceDetail": "asr"},
        ],
    }

    def ref(kind: str, body: dict[str, Any]) -> dict[str, Any]:
        payload = json.dumps(body).encode()
        return {
            "kind": kind,
            "storageKey": f"runs/398021847361024/scene_transcript_mapping/a1/{kind}.json",
            "byteSize": len(payload),
            "contentHash": hashlib.sha256(payload).hexdigest(),
        }

    segments_ref = ref("transcript_segments", original)
    decisions = {
        "schemaVersion": "npick.transcript.decisions/v1",
        "segmentsArtifact": segments_ref,
        "decisions": [
            {
                "segmentId": "s1",
                "selected": True,
                "reasonCode": "PREFERRED_SUBTITLE",
                "conflictsWith": [],
            },
            {
                "segmentId": "s2",
                "selected": False,
                "reasonCode": "OVERLAPS_HIGHER_PRIORITY",
                "conflictsWith": ["s1"],
            },
        ],
    }
    decisions_ref = ref("transcript_decisions", decisions)
    upstream = {
        "scene_transcript_mapping": {
            "transcript": {"segmentsArtifact": segments_ref, "decisionsArtifact": decisions_ref},
            "scenes": [
                {"sceneIndex": 0, "segments": [{"segmentId": "s1", "overlapMs": 1500}]},
                {"sceneIndex": 1, "segments": [{"segmentId": "s1", "overlapMs": 1500}]},
                {"sceneIndex": 2, "segments": []},
            ],
        },
        "frameExtraction": {
            "scenes": [
                {
                    "sceneIndex": index,
                    "keyframes": [
                        {
                            "sceneIndex": index,
                            "timestampMs": index * 1500,
                            "storageKey": f"f{index}",
                        }
                    ],
                }
                for index in range(3)
            ],
            "imageWidth": 1920,
            "imageHeight": 1080,
        },
        # 오래된 별칭 대신 최종 snapshot만 사용한다.
        "transcript": {"segmentsArtifact": {"storageKey": "stale.json"}},
    }
    return upstream, {segments_ref["storageKey"]: original, decisions_ref["storageKey"]: decisions}


def frames() -> tuple[SceneKeyframes, ...]:
    return tuple(SceneKeyframes(i, (KeyframeRef(i, i * 1500, f"f{i}"),)) for i in range(3))


def test_mapping_reuses_selected_original_and_preserves_empty_scene() -> None:
    upstream, documents = sample()
    result = attach_mapped_transcripts(frames(), upstream, documents)
    assert [len(scene.transcripts) for scene in result] == [1, 1, 0]
    assert result[0].transcripts[0].t == "부산 축제 소개"
    assert result[1].transcripts[0].ref.scene_index == 1
    assert result[0].transcripts[0].ref.segment_id == result[1].transcripts[0].ref.segment_id
    assert "제외된 ASR" not in str(result)


@pytest.mark.parametrize(
    "fault",
    [
        "unknown",
        "excluded",
        "duplicate",
        "missing_scene",
        "extra_scene",
        "wrong_snapshot",
        "no_document",
        "zero_overlap",
        "long_overlap",
    ],
)
def test_invalid_mapping_cannot_reach_vlm(fault: str) -> None:
    upstream, documents = sample()
    mapping = upstream["scene_transcript_mapping"]
    links = mapping["scenes"][0]["segments"]
    if fault == "unknown":
        links[0]["segmentId"] = "missing"
    elif fault == "excluded":
        links[0]["segmentId"] = "s2"
    elif fault == "duplicate":
        links.append(dict(links[0]))
    elif fault == "missing_scene":
        mapping["scenes"].pop()
    elif fault == "extra_scene":
        mapping["scenes"].append({"sceneIndex": 9, "segments": []})
    elif fault == "wrong_snapshot":
        decisions = documents[mapping["transcript"]["decisionsArtifact"]["storageKey"]]
        decisions["segmentsArtifact"] = {
            **decisions["segmentsArtifact"],
            "storageKey": "other.json",
        }
    elif fault == "no_document":
        documents.clear()
    else:
        links[0]["overlapMs"] = 0 if fault == "zero_overlap" else 3001
    with pytest.raises(UpstreamOutputInvalidError):
        attach_mapped_transcripts(frames(), upstream, documents)


@pytest.mark.asyncio
async def test_existing_artifact_loader_and_vlm_handler_use_final_mapping(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upstream, documents = sample()
    # 한 장면 호출만 검사; 최종 mapping에는 그 장면이 반드시 있어야 한다.
    upstream["frameExtraction"]["scenes"] = upstream["frameExtraction"]["scenes"][:1]
    upstream["scene_transcript_mapping"]["scenes"] = upstream["scene_transcript_mapping"]["scenes"][
        :1
    ]
    job = JobAssignment.model_validate(
        make_job(
            stage="vlm_metadata",
            inputs={
                "media": {"storageKey": "clips/1/source.mp4"},
                "upstream": upstream,
            },
        )
    )
    for ref in transcript_refs(upstream, stage="vlm_metadata"):
        fake_backend.enqueue(
            "artifact_get",
            httpx2.Response(200, content=json.dumps(documents[ref.storage_key]).encode()),
        )
    loaded = await resolve_transcripts(job, MediaResolver(None, job_client))
    assert len(fake_backend.calls("artifact_get")) == 2
    client = RecordingClient(["kf_1", "tr_1"])
    monkeypatch.setattr(registry, "_vlm_client", lambda _: client)
    image = tmp_path / "frame.jpg"
    image.write_bytes(b"fake image read only by test client")
    output = registry._run_vlm_metadata(
        registry.StageContext(
            stage="vlm_metadata",
            video_path=None,
            storage_key="source.mp4",
            work_dir=tmp_path,
            output_key_prefix="out/",
            upstream=upstream,
            upstream_files={"f0": image},
            artifact_documents=loaded,
        )
    )
    assert "부산 축제 소개" in client.user_prompt
    assert "제외된 ASR" not in client.user_prompt
    assert output.output["scenes"][0]["caption"]["evidence"][1]["segmentId"] == "s1"
    assert output.versions.output_schema_version == "npick.stage.vlm_metadata.output/v2"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "stage", ["entity_extraction", "text_embedding", "indexing", "vlm_metadata"]
)
async def test_mapping_validation_is_scoped_to_vlm(
    stage: str, job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    job = JobAssignment.model_validate(
        make_job(
            stage=stage,
            inputs={
                "media": {"storageKey": "clips/1/source.mp4"},
                "upstream": {"scene_transcript_mapping": {}},
            },
        )
    )
    if stage == "vlm_metadata":
        with pytest.raises(UpstreamOutputInvalidError):
            await resolve_transcripts(job, MediaResolver(None, job_client))
    else:
        assert await resolve_transcripts(job, MediaResolver(None, job_client)) == {}
    assert not fake_backend.calls("artifact_get")


@pytest.mark.parametrize("invalid", [False, True])
def test_mapping_ignores_nested_extensions_but_validates_known_fields(invalid: bool) -> None:
    from copy import deepcopy

    from pydantic import ValidationError

    from npick_worker.jobs.transcripts import SceneTranscriptMappingOutput

    upstream, documents = sample()
    mapping = deepcopy(upstream["scene_transcript_mapping"])
    for obj in [
        mapping,
        mapping["transcript"],
        mapping["transcript"]["segmentsArtifact"],
        mapping["transcript"]["decisionsArtifact"],
        mapping["scenes"][0],
        mapping["scenes"][0]["segments"][0],
    ]:
        obj["futureField"] = {"extra": True}
    upstream["scene_transcript_mapping"] = mapping
    with pytest.raises(ValidationError):
        SceneTranscriptMappingOutput.model_validate(mapping)
    if invalid:
        mapping["scenes"][0]["segments"][0]["overlapMs"] = 0
        with pytest.raises(UpstreamOutputInvalidError):
            attach_mapped_transcripts(frames(), upstream, documents)
    else:
        refs = transcript_refs(upstream, stage="vlm_metadata")
        assert (
            refs[0].model_dump(by_alias=True) == documents[refs[1].storage_key]["segmentsArtifact"]
        )
        result = attach_mapped_transcripts(frames(), upstream, documents)
        assert result[0].transcripts[0].t == "부산 축제 소개"
