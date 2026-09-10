"""Resolve structured upstream artifacts through the same HTTP integrity boundary as media."""

import json
from collections.abc import Mapping
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any

from npick_worker.jobs.errors import UpstreamOutputInvalidError
from npick_worker.jobs.media import MediaResolver
from npick_worker.jobs.models import JobAssignment, MediaRef
from npick_worker.jobs.transcripts import (
    TranscriptDecisions,
    TranscriptSegments,
    transcript_refs,
    validate_snapshot,
)


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def validate_key(key: str) -> None:
    if (
        not key
        or PurePosixPath(key).is_absolute()
        or PureWindowsPath(key).drive
        or "\\" in key
        or ":" in key
        or any(part in {"", ".", ".."} for part in key.split("/"))
    ):
        raise ValueError("invalid relative storageKey")


async def resolve_transcripts(
    job: JobAssignment, media: MediaResolver
) -> Mapping[str, Mapping[str, Any]]:
    try:
        refs = transcript_refs(job.inputs.upstream)
        documents: dict[str, Mapping[str, Any]] = {}
        for ref in refs:
            validate_key(ref.storage_key)
            if not ref.storage_key.startswith(f"runs/{job.pipeline_run_id}/"):
                raise ValueError("transcript belongs to another run")
            if job.stage == "transcript_selection" and not ref.storage_key.startswith(
                job.output_key_prefix
            ):
                raise ValueError("prepared transcript belongs to another attempt")
            if len(ref.content_hash) != 64 or any(
                c not in "0123456789abcdef" for c in ref.content_hash
            ):
                raise ValueError("invalid artifact SHA-256")
            # Artifact transport is always authenticated HTTP, even with shared source media.
            async with media.resolve(
                job.pipeline_run_id,
                MediaRef(
                    storage_key=ref.storage_key,
                    content_hash=ref.content_hash,
                    size_bytes=ref.byte_size,
                    transport="http",
                ),
            ) as resolved:
                documents[ref.storage_key] = json.loads(
                    Path(resolved.path).read_bytes(), object_pairs_hook=_unique_object
                )
        if refs:
            segments = TranscriptSegments.model_validate(documents[refs[0].storage_key])
            if len(refs) == 2:
                decisions = TranscriptDecisions.model_validate(documents[refs[1].storage_key])
                validate_snapshot(refs[0], segments, decisions)
        return documents
    except (ValueError, TypeError) as exc:
        raise UpstreamOutputInvalidError("invalid transcript artifact or reference") from exc
