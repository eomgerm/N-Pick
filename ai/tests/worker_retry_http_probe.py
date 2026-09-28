"""Failure injection only; the HTTP client, runner and backend persistence are real."""

import asyncio
import sys
from pathlib import Path
from unittest.mock import patch

from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import LeaseLostError
from npick_worker.jobs.media import MediaResolver
from npick_worker.jobs.models import CompleteAck, StageResult, WorkerDevice
from npick_worker.jobs.registry import StageContext, StageHandler, StageOutcome
from npick_worker.jobs.runner import ClaimOutcome, JobRunner
from npick_worker.jobs.versions import StageVersion


async def exercise(url: str, root: Path) -> None:
    client = JobApiClient(
        base_url=url,
        token="t" * 32,
        worker_id="retry-test",
        connect_timeout=5,
        read_timeout=10,
        poll_wait_seconds=0,
        max_backoff_seconds=0.01,
    )
    replacement = JobApiClient(
        base_url=url,
        token="t" * 32,
        worker_id="retry-replacement",
        connect_timeout=5,
        read_timeout=10,
        poll_wait_seconds=0,
        max_backoff_seconds=0.01,
    )
    results: list[tuple[str, StageResult, CompleteAck]] = []
    prefixes: list[str] = []
    original_complete = client.complete
    replacement_complete = replacement.complete

    async def capture(run: str, stage: str, result: StageResult) -> CompleteAck:
        ack = await original_complete(run, stage, result)
        results.append((run, result, ack))
        assert (await original_complete(run, stage, result)).duplicate
        return ack

    async def capture_replacement(run: str, stage: str, result: StageResult) -> CompleteAck:
        ack = await replacement_complete(run, stage, result)
        results.append((run, result, ack))
        assert (await replacement_complete(run, stage, result)).duplicate
        return ack

    def stage(context: StageContext) -> StageOutcome:
        prefix = context.output_key_prefix
        prefixes.append(prefix)
        if "/915/" in prefix:
            raise ValueError("injected permanent invalid input")
        if "/a1/" in prefix or "/914/" in prefix or context.stage == "asr":
            raise TimeoutError("injected temporary AI failure")
        return StageOutcome(
            output={
                "mediaDurationMs": 1000,
                "frameRate": 25,
                "scenes": [{"sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 1000}],
            },
            versions=StageVersion(
                stage_version="npick.stage.scene_detection/v1:aaaaaaaa",
                output_schema_version="npick.stage.scene_detection.output/v1",
            ),
        )

    versions = {name: f"npick.stage.{name}/v1:aaaaaaaa" for name in ("scene_detection", "asr")}
    try:
        with (
            patch(
                "npick_worker.jobs.registry.resolve",
                side_effect=lambda name: StageHandler(name, stage, needs_video=False),
            ),
            patch("npick_worker.jobs.registry.capability_versions", return_value=versions),
            patch.object(client, "complete", side_effect=capture),
            patch.object(replacement, "complete", side_effect=capture_replacement),
        ):
            runner = JobRunner(
                client=client,
                media=MediaResolver(None, client),
                worker_id="retry-test",
                fleet="local",
                poll_wait_seconds=0,
                heartbeat_seconds=0.01,
                shared_media_volume=False,
                media_root=None,
                device=WorkerDevice(kind="cpu"),
            )
            assert await runner.run_once() is ClaimOutcome.FAILED
            other_runner = JobRunner(
                client=replacement,
                media=MediaResolver(None, replacement),
                worker_id="retry-replacement",
                fleet="local",
                poll_wait_seconds=0,
                heartbeat_seconds=0.01,
                shared_media_volume=False,
                media_root=None,
                device=WorkerDevice(kind="cpu"),
            )
            assigned = await replacement.claim(other_runner._claim_request())
            assert assigned.assigned and assigned.job is not None and assigned.lease is not None
            assert assigned.job.attempt == 2 and assigned.job.max_attempts == 2
            # An accepted completion can be replayed after another worker owns the new attempt.
            replay = await original_complete("913", "scene_detection", results[0][1])
            assert replay == results[0][2].model_copy(update={"duplicate": True})
            await other_runner._process(assigned.job, assigned.lease)
            for _ in range(5):
                assert await runner.run_once() is not ClaimOutcome.IDLE
            assert await runner.run_once() is ClaimOutcome.IDLE
        assert [(run, result.attempt, result.status) for run, result, _ in results] == [
            ("913", 1, "failed"),
            ("913", 2, "succeeded"),
            ("914", 1, "failed"),
            ("914", 2, "failed"),
            ("915", 1, "failed"),
            ("916", 1, "failed"),
            ("916", 2, "failed"),
        ]
        assert len(prefixes) == len(set(prefixes))
        assigned_ids = results[1][2].assigned_ids
        assert assigned_ids is not None and assigned_ids.scenes[0].scene_index == 0
        replay = await original_complete("913", "scene_detection", results[0][1])
        assert replay == results[0][2].model_copy(update={"duplicate": True})
        try:
            await replacement_complete("913", "scene_detection", results[0][1])
        except LeaseLostError:
            pass
        else:
            raise AssertionError("a different worker replayed an accepted completion")
        (root / "retry-probe-ok").write_text("real runner and HTTP; injected AI stages")
    finally:
        await client.aclose()
        await replacement.aclose()


if __name__ == "__main__":
    asyncio.run(exercise(sys.argv[1], Path(sys.argv[2])))
