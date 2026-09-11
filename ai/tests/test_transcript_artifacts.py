"""Artifact integrity, snapshot references and offline fencing preflight."""

import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx2
import pytest

from npick_worker.jobs.artifacts import resolve_transcripts
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import (
    ContentHashMismatchError,
    InputDownloadError,
    LeaseLostError,
    UpstreamOutputInvalidError,
)
from npick_worker.jobs.media import MediaResolver
from npick_worker.jobs.models import (
    ArtifactRef,
    JobAssignment,
    LeaseGrant,
    StageResult,
    WorkerDevice,
)
from npick_worker.jobs.offline import import_result
from npick_worker.jobs.registry import StageContext, StageHandler, StageOutcome
from npick_worker.jobs.runner import JobRunner
from npick_worker.jobs.transcripts import TranscriptDecisions, TranscriptSegments, validate_snapshot
from npick_worker.jobs.versions import StageVersion

from .conftest import FakeBackend, make_job, make_lease


def original() -> dict[str, Any]:
    return {
        "schemaVersion": "npick.transcript.segments/v1",
        "segments": [
            {
                "segmentId": "uploaded-0",
                "s": 0,
                "e": 20000,
                "t": "원문",
                "sourceDetail": "uploaded",
            },
            {
                "segmentId": "asr-0",
                "s": 18000,
                "e": 23000,
                "t": "보관용 원문",
                "sourceDetail": "asr",
            },
        ],
    }


def reference() -> ArtifactRef:
    return ArtifactRef(
        kind="transcript_segments",
        storage_key="runs/398021847361024/transcript_selection/a1/input.json",
        byte_size=len(json.dumps(original()).encode()),
        content_hash=hashlib.sha256(json.dumps(original()).encode()).hexdigest(),
    )


def decisions() -> dict[str, Any]:
    return {
        "schemaVersion": "npick.transcript.decisions/v1",
        "segmentsArtifact": reference().model_dump(by_alias=True),
        "decisions": [
            {
                "segmentId": "uploaded-0",
                "selected": True,
                "reasonCode": "PREFERRED_SUBTITLE",
                "conflictsWith": [],
            },
            {
                "segmentId": "asr-0",
                "selected": False,
                "reasonCode": "OVERLAPS_HIGHER_PRIORITY",
                "conflictsWith": ["uploaded-0"],
            },
        ],
    }


def test_snapshot_keeps_full_excluded_original_and_validates_conflicts() -> None:
    segments = TranscriptSegments.model_validate(original())
    validate_snapshot(reference(), segments, TranscriptDecisions.model_validate(decisions()))
    assert segments.segments[1].e == 23000
    for mutation in ("missing", "duplicate", "foreign", "snapshot", "reason"):
        payload = decisions()
        if mutation == "missing":
            payload["decisions"].pop()
        elif mutation == "duplicate":
            payload["decisions"].append(payload["decisions"][0])
        elif mutation == "foreign":
            payload["decisions"][1]["conflictsWith"] = ["another"]
        elif mutation == "snapshot":
            payload["segmentsArtifact"]["contentHash"] = "0" * 64
        else:
            payload["decisions"][1]["reasonCode"] = "ASR_SUPPLEMENT"
        with pytest.raises(ValueError):
            validate_snapshot(reference(), segments, TranscriptDecisions.model_validate(payload))


@pytest.mark.asyncio
@pytest.mark.parametrize("needs_video", [True, False])
async def test_handler_consumes_transcript_independently_of_video_requirement(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    needs_video: bool,
) -> None:
    transcript = {
        "segmentsArtifact": reference().model_dump(by_alias=True),
        "asrRequired": True,
        "candidateRanges": [{"s": 20000, "e": 25000}],
        "reasonCode": "UNCOVERED_RANGES",
    }
    job = JobAssignment.model_validate(
        make_job(
            stage="asr",
            outputKeyPrefix="runs/398021847361024/asr/a1/",
            inputs={
                "media": {"storageKey": "clips/1/source.mp4"},
                "upstream": {"transcript": transcript},
            },
        )
    )
    if needs_video:
        fake_backend.enqueue("artifact_get", httpx2.Response(200, content=b"test-media"))
    fake_backend.enqueue(
        "artifact_get", httpx2.Response(200, content=json.dumps(original()).encode())
    )
    consumed: list[int] = []

    def mock_asr(context: StageContext) -> StageOutcome:
        if needs_video:
            assert context.video_path is not None
            assert context.video_path.read_bytes() == b"test-media"
        else:
            assert context.video_path is None
        candidates = context.upstream["transcript"]["candidateRanges"]
        consumed.extend(candidate["e"] - candidate["s"] for candidate in candidates)
        assert context.artifact_documents[reference().storage_key] == original()
        # Predetermined empty recognition result; no VAD or ASR algorithm is claimed.
        return StageOutcome(
            output={"segments": []},
            versions=StageVersion(
                stage_version="npick.stage.asr/v1:aaaaaaaa",
                output_schema_version="npick.stage.asr.output/v1",
            ),
        )

    monkeypatch.setattr(
        "npick_worker.jobs.registry.resolve",
        lambda _: StageHandler("asr", mock_asr, needs_video=needs_video),
    )
    runner = JobRunner(
        client=job_client,
        media=MediaResolver(None, job_client),
        worker_id=job_client.worker_id,
        fleet="local",
        poll_wait_seconds=0,
        heartbeat_seconds=1,
        shared_media_volume=False,
        media_root=None,
        device=WorkerDevice(kind="cpu"),
    )
    outcome = await runner._run_stage(job, tmp_path)
    assert consumed == [5000]
    assert outcome.output == {"segments": []}


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["size", "hash", "run", "attempt", "path"])
async def test_bad_artifacts_never_reach_stage(
    job_client: JobApiClient, fake_backend: FakeBackend, tmp_path: Path, failure: str
) -> None:
    ref = reference().model_dump(by_alias=True)
    if failure == "size":
        ref["byteSize"] += 1
    elif failure == "hash":
        ref["contentHash"] = "0" * 64
    elif failure == "run":
        ref["storageKey"] = "runs/999/transcript_selection/a1/input.json"
    elif failure == "attempt":
        ref["storageKey"] = "runs/398021847361024/transcript_selection/a2/input.json"
    else:
        ref["storageKey"] = "runs/398021847361024/transcript_selection/a1/../input.json"
    job = JobAssignment.model_validate(
        make_job(
            stage="transcript_selection",
            outputKeyPrefix="runs/398021847361024/transcript_selection/a1/",
            inputs={
                "media": {"storageKey": "clips/1/source.mp4"},
                "upstream": {"transcript": {"segmentsArtifact": ref}},
            },
        )
    )
    fake_backend.enqueue(
        "artifact_get", httpx2.Response(200, content=json.dumps(original()).encode())
    )
    with pytest.raises((UpstreamOutputInvalidError, InputDownloadError, ContentHashMismatchError)):
        await resolve_transcripts(job, MediaResolver(None, job_client))
    if failure in {"run", "attempt", "path"}:
        assert not fake_backend.calls("artifact_get")


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["attempt", "lease", "run", "expired"])
async def test_offline_rejects_stale_assignment_before_upload_or_complete(
    job_client: JobApiClient, fake_backend: FakeBackend, tmp_path: Path, failure: str
) -> None:
    job = JobAssignment.model_validate(
        make_job(outputSchemaVersion="npick.stage.scene_detection.output/v1")
    )
    lease = LeaseGrant.model_validate(make_lease())
    now = datetime.now(UTC)
    result = StageResult(
        lease_id="another-lease" if failure == "lease" else lease.lease_id,
        idempotency_key="999:scene_detection:1" if failure == "run" else job.idempotency_key,
        stage=job.stage,
        attempt=2 if failure == "attempt" else 1,
        status="succeeded",
        started_at=now,
        finished_at=now,
        duration_ms=0,
        versions=StageVersion(
            stage_version="npick.stage.scene_detection/v1:aaaaaaaa",
            output_schema_version="npick.stage.scene_detection.output/v1",
        ),
        output={"scenes": []},
    )
    if failure == "expired":
        fake_backend.enqueue_status("heartbeat", 409, code="JOB_409_002")
    with pytest.raises((UpstreamOutputInvalidError, LeaseLostError)):
        await import_result(job_client, job, lease, result, tmp_path)
    assert not fake_backend.calls("artifact_put")
    assert not fake_backend.calls("complete")
