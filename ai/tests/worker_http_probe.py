"""Invoked by the Java HTTP integration test; the stage algorithm is a test double."""

import asyncio
import hashlib
import json
import sys
from datetime import UTC, datetime
from pathlib import Path
from unittest.mock import patch

from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.media import MediaResolver
from npick_worker.jobs.models import (
    ArtifactRef,
    CompleteAck,
    StageResult,
    WorkerDevice,
)
from npick_worker.jobs.offline import import_result
from npick_worker.jobs.registry import PendingUpload, StageContext, StageHandler, StageOutcome
from npick_worker.jobs.runner import JobRunner
from npick_worker.jobs.versions import StageVersion

VERSION = "npick.stage.transcript_selection/v1:aaaaaaaa"


async def exercise(url: str, bundle: Path) -> None:
    client = JobApiClient(
        base_url=url,
        token="t" * 32,
        worker_id="http-test",
        connect_timeout=5,
        read_timeout=10,
        poll_wait_seconds=0,
        max_backoff_seconds=0.01,
    )
    completed: list[StageResult] = []
    original_complete = client.complete

    async def capture(run: str, stage: str, result: StageResult) -> CompleteAck:
        completed.append(result)
        return await original_complete(run, stage, result)

    def stage(context: StageContext) -> StageOutcome:
        upstream = context.upstream["transcript"]
        document = context.artifact_documents[upstream["segmentsArtifact"]["storageKey"]]
        assert document["segments"][0]["t"] == "실제 단계 입력"
        assert context.video_path is not None
        assert context.video_path.read_bytes() == (bundle / "clips/802/source.mp4").read_bytes()
        # A predetermined mock result, not a transcript selection implementation.
        uploads: list[PendingUpload] = []

        def artifact(kind: str, payload: dict[str, object]) -> ArtifactRef:
            body = json.dumps(payload, ensure_ascii=False).encode()
            key = context.output_key_prefix + kind + "-result.json"
            local = context.work_dir / (kind + ".json")
            local.write_bytes(body)
            saved = bundle / key
            saved.parent.mkdir(parents=True, exist_ok=True)
            saved.write_bytes(body)
            ref = ArtifactRef(
                kind=kind,
                storage_key=key,
                byte_size=len(body),
                content_hash=hashlib.sha256(body).hexdigest(),
            )
            uploads.append(
                PendingUpload(ref=ref, local_path=local, content_type="application/json")
            )
            return ref

        segments = artifact("transcript_segments", dict(document))
        decisions = artifact(
            "transcript_decisions",
            {
                "schemaVersion": "npick.transcript.decisions/v1",
                "segmentsArtifact": segments.model_dump(by_alias=True),
                "decisions": [
                    {
                        "segmentId": "uploaded-0",
                        "selected": True,
                        "reasonCode": "PREFERRED_SUBTITLE",
                        "conflictsWith": [],
                    }
                ],
            },
        )
        return StageOutcome(
            output={
                "transcript": {
                    "segmentsArtifact": segments.model_dump(by_alias=True),
                    "decisionsArtifact": decisions.model_dump(by_alias=True),
                    "asrRequired": False,
                    "candidateRanges": [],
                    "reasonCode": "SUBTITLE_COVERED",
                }
            },
            versions=StageVersion(
                stage_version=VERSION,
                output_schema_version="npick.stage.transcript_selection.output/v1",
            ),
            uploads=tuple(uploads),
        )

    try:
        with (
            patch(
                "npick_worker.jobs.registry.resolve",
                return_value=StageHandler("transcript_selection", stage),
            ),
            patch(
                "npick_worker.jobs.registry.capability_versions",
                return_value={"transcript_selection": VERSION},
            ),
            patch.object(client, "complete", side_effect=capture),
        ):
            runner = JobRunner(
                client=client,
                media=MediaResolver(None, client),
                worker_id="http-test",
                fleet="local",
                poll_wait_seconds=0,
                heartbeat_seconds=0.01,
                shared_media_volume=False,
                media_root=None,
                device=WorkerDevice(kind="cpu"),
            )
            assert await runner.run_once()
            assert completed[0].status == "succeeded", completed[0].error
            run_id = completed[0].idempotency_key.split(":")[0]
            ack = await original_complete(run_id, "transcript_selection", completed[0])
            assert ack.accepted and ack.duplicate
            (bundle / "live-result.json").write_text(completed[0].model_dump_json(by_alias=True))

            # A different current assignment exercises first-time offline import. A completed
            # lease only permits duplicate complete; it does not authorize re-uploading files.
            assigned = await client.claim(runner._claim_request())
            assert assigned.job is not None and assigned.lease is not None
            job, lease = assigned.job, assigned.lease
            client.bind_artifact_lease(job.pipeline_run_id, lease.lease_id)
            work = bundle / "offline-work"
            work.mkdir()
            outcome = await runner._run_stage(job, work)
            result = StageResult(
                lease_id=lease.lease_id,
                idempotency_key=job.idempotency_key,
                stage=job.stage,
                attempt=job.attempt,
                status="succeeded",
                started_at=datetime.now(UTC),
                finished_at=datetime.now(UTC),
                duration_ms=0,
                versions=outcome.versions,
                output=outcome.output,
                artifacts=tuple(upload.ref for upload in outcome.uploads),
            )
            # Real HTTP rejections before any accepted completion; the executor must stay running.
            prefix = f"/api/v1/internal/jobs/{job.pipeline_run_id}"
            headers = {"X-Job-Lease-Id": lease.lease_id, "X-Content-SHA256": "0" * 64}
            for key, expected in [
                (job.output_key_prefix + "bad.json", 400),
                (job.output_key_prefix.replace("/a1/", "/a2/") + "bad.json", 403),
                ("runs/999/transcript_selection/a1/bad.json", 403),
            ]:
                response = await client._client.put(
                    prefix + "/artifacts/" + key, content=b"{}", headers=headers
                )
                assert response.status_code == expected, response.text
            response = await client._client.get(
                prefix + "/artifacts", params={"key": "../outside"}, headers=headers
            )
            assert response.status_code == 403, response.text
            bad = result.model_dump(by_alias=True, mode="json")
            bad["attempt"] = 2
            response = await client._client.post(
                prefix + "/stages/transcript_selection/complete",
                json=bad,
                headers={"Idempotency-Key": result.idempotency_key},
            )
            assert response.status_code == 400, response.text
            # Self-consistent files that silently drop the supplied original are still invalid.
            empty_refs: list[dict[str, object]] = []
            for kind in ("transcript_segments", "transcript_decisions"):
                payload: dict[str, object] = (
                    {"schemaVersion": "npick.transcript.segments/v1", "segments": []}
                    if not empty_refs
                    else {
                        "schemaVersion": "npick.transcript.decisions/v1",
                        "segmentsArtifact": empty_refs[0],
                        "decisions": [],
                    }
                )
                body = json.dumps(payload).encode()
                key = job.output_key_prefix + "dropped-" + kind + ".json"
                digest = hashlib.sha256(body).hexdigest()
                await client.upload_artifact(
                    job.pipeline_run_id,
                    key,
                    body,
                    content_type="application/json",
                    content_sha256=digest,
                )
                empty_refs.append(
                    {"kind": kind, "storageKey": key, "byteSize": len(body), "contentHash": digest}
                )
            dropped = result.model_dump(by_alias=True, mode="json")
            dropped["artifacts"] = empty_refs
            dropped["output"]["transcript"]["segmentsArtifact"] = empty_refs[0]
            dropped["output"]["transcript"]["decisionsArtifact"] = empty_refs[1]
            response = await client._client.post(
                prefix + "/stages/transcript_selection/complete",
                json=dropped,
                headers={"Idempotency-Key": result.idempotency_key},
            )
            assert response.status_code == 400, response.text
            ack = await import_result(client, job, lease, result, bundle)
            assert ack.accepted and not ack.duplicate
            duplicate = await original_complete(job.pipeline_run_id, job.stage, result)
            assert duplicate.duplicate
    finally:
        await client.aclose()


if __name__ == "__main__":
    asyncio.run(exercise(sys.argv[1], Path(sys.argv[2])))
