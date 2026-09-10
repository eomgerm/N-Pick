"""Import an offline result using a current BE assignment and the live completion contract."""

import asyncio
import hashlib
from pathlib import Path

from npick_worker.jobs.artifacts import validate_key
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import LeaseLostError, UpstreamOutputInvalidError
from npick_worker.jobs.models import (
    CompleteAck,
    HeartbeatRequest,
    JobAssignment,
    LeaseGrant,
    StageResult,
)


async def import_result(
    client: JobApiClient,
    job: JobAssignment,
    lease: LeaseGrant,
    result: StageResult,
    bundle_root: Path,
) -> CompleteAck:
    """Never rebind a stale bundle to a new attempt or manufacture a successful result."""
    if (
        result.stage != job.stage
        or result.attempt != job.attempt
        or result.lease_id != lease.lease_id
        or result.idempotency_key != job.idempotency_key
        or result.versions.output_schema_version != job.output_schema_version
    ):
        raise UpstreamOutputInvalidError("offline result does not match its assignment")
    root = bundle_root.resolve()
    pending: list[tuple[str, bytes, str, str]] = []
    seen: set[str] = set()
    for ref in result.artifacts:
        validate_key(ref.storage_key)
        path = (root / ref.storage_key).resolve()
        if (
            not ref.storage_key.startswith(job.output_key_prefix)
            or not path.is_relative_to(root)
            or ref.storage_key in seen
        ):
            raise UpstreamOutputInvalidError("offline artifact is outside this attempt")
        seen.add(ref.storage_key)
        body = path.read_bytes()
        if len(body) != ref.byte_size or hashlib.sha256(body).hexdigest() != ref.content_hash:
            raise UpstreamOutputInvalidError("offline artifact integrity mismatch")
        content_type = "image/jpeg" if ref.kind == "keyframe" else "application/json"
        pending.append((ref.storage_key, body, ref.content_hash, content_type))

    async def keep_alive() -> None:
        while True:
            ack = await client.heartbeat(
                job.pipeline_run_id,
                job.stage,
                HeartbeatRequest(lease_id=lease.lease_id, elapsed_ms=0),
            )
            if ack.command == "abort":
                raise LeaseLostError("offline import lease was revoked")
            await asyncio.sleep(lease.heartbeat_interval_ms / 1000)

    async def transfer() -> CompleteAck:
        for key, body, digest, content_type in pending:
            await client.upload_artifact(
                job.pipeline_run_id, key, body, content_type=content_type, content_sha256=digest
            )
        return await client.complete(job.pipeline_run_id, job.stage, result)

    # Check the lease before any upload; a stale offline file is not permission to write.
    ack = await client.heartbeat(
        job.pipeline_run_id, job.stage, HeartbeatRequest(lease_id=lease.lease_id, elapsed_ms=0)
    )
    if ack.command == "abort":
        raise LeaseLostError("offline import lease was revoked")
    client.bind_artifact_lease(job.pipeline_run_id, lease.lease_id)
    heartbeat = asyncio.create_task(keep_alive())
    upload = asyncio.create_task(transfer())
    try:
        done, _ = await asyncio.wait({heartbeat, upload}, return_when=asyncio.FIRST_COMPLETED)
        if heartbeat in done:
            await heartbeat
        return await upload
    finally:
        upload.cancel()
        heartbeat.cancel()
        await asyncio.gather(upload, heartbeat, return_exceptions=True)
        client.release_artifact_lease(job.pipeline_run_id)
