"""claim → 실행 → complete 왕복.

헤드라인 테스트는 실제 mp4 를 실제 `detect_scenes` 로 처리해 가짜 BE 까지 보낸다.
BE 잡 API 는 아직 구현되지 않았으므로 상대는 계약 모양으로만 응답하는 fake 다.
"""

import asyncio
import hashlib
import json
import threading
from pathlib import Path
from typing import Any

import httpx2
import pytest

from npick_worker.jobs import registry
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.media import MediaResolver
from npick_worker.jobs.models import ArtifactRef, JobAssignment, LeaseGrant, WorkerDevice
from npick_worker.jobs.registry import (
    PendingUpload,
    StageContext,
    StageHandler,
    StageOutcome,
)
from npick_worker.jobs.runner import ClaimOutcome, JobRunner, _JobControl
from npick_worker.jobs.versions import StageVersion

from .conftest import FakeBackend, envelope, make_job, make_lease

RUN_ID = "398021847361024"

#: 10fps 합성 영상. 20프레임 블록 하나가 2000ms 다.
ROUNDTRIP_BLOCKS = [("bars", 20), ("white", 20), ("noise", 20)]

#: JPEG 파일의 시작 바이트. 인코더를 바꿔도 이건 바뀌지 않는다.
JPEG_MAGIC = bytes.fromhex("ffd8ff")


@pytest.fixture(autouse=True)
def _fast_heartbeat(monkeypatch: pytest.MonkeyPatch) -> None:
    """heartbeat 주기를 기다리지 않는다. 잔 시간이 아니라 호출 여부가 관심사다."""
    real_sleep = asyncio.sleep

    async def quick_sleep(seconds: float) -> None:
        await real_sleep(0)

    monkeypatch.setattr(asyncio, "sleep", quick_sleep)


@pytest.fixture
def media_root(tmp_path: Path) -> Path:
    root = tmp_path / "media"
    root.mkdir()
    return root


def _runner(client: JobApiClient, media_root: Path | None) -> JobRunner:
    return JobRunner(
        client=client,
        media=MediaResolver(media_root, client),
        worker_id="test-worker",
        fleet="local",
        poll_wait_seconds=1,
        heartbeat_seconds=0.01,
        shared_media_volume=media_root is not None,
        media_root=media_root,
        device=WorkerDevice(kind="cpu"),
    )


def _plant_default_media(media_root: Path) -> None:
    """make_job() 기본 배정이 가리키는 입력을 만들어 둔다.

    파일이 없으면 단계에 닿기 전에 실패해서, "실행 중에 중단 신호가 왔다" 를
    재현하려는 테스트의 전제가 무너진다.
    """
    target = media_root / "clips/398021840012345/source.mp4"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(b"placeholder")


def _complete_body(fake_backend: FakeBackend) -> dict[str, Any]:
    body: dict[str, Any] = json.loads(fake_backend.calls("complete")[0].content)
    return body


# ── 왕복 ─────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_round_trip_scene_detection_job(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    make_video: Any,
) -> None:
    """실제 영상 → 실제 detect_scenes → 계약 봉투가 BE 에 도착한다."""
    video = make_video("roundtrip", ROUNDTRIP_BLOCKS)
    storage_key = "clips/a/source.mp4"
    target = media_root / storage_key
    target.parent.mkdir(parents=True)
    target.write_bytes(video.read_bytes())

    fake_backend.enqueue_claim(
        make_job(inputs={"media": {"storageKey": storage_key, "transport": "shared-volume"}})
    )

    assert await _runner(job_client, media_root).run_once() is ClaimOutcome.SUCCEEDED

    body = _complete_body(fake_backend)
    assert body["stage"] == "scene_detection"
    assert body["status"] == "succeeded"
    assert body["envelopeVersion"] == "stage-result/v1"
    # 20프레임 블록 셋 = 2000ms 씩 세 구간.
    assert [(s["startTimeMs"], s["endTimeMs"]) for s in body["output"]["scenes"]] == [
        (0, 2000),
        (2000, 4000),
        (4000, 6000),
    ]
    assert body["output"]["mediaDurationMs"] == 6000


@pytest.mark.asyncio
async def test_round_trip_reports_real_versions(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    make_video: Any,
) -> None:
    """버전이 자리표시자가 아니라 실제 엔진 값이어야 검증이 성립한다."""
    video = make_video("versions", [("bars", 20), ("white", 20)])
    target = media_root / "clips/a/source.mp4"
    target.parent.mkdir(parents=True)
    target.write_bytes(video.read_bytes())
    fake_backend.enqueue_claim(
        make_job(
            inputs={"media": {"storageKey": "clips/a/source.mp4", "transport": "shared-volume"}}
        )
    )

    await _runner(job_client, media_root).run_once()

    versions = _complete_body(fake_backend)["versions"]
    assert versions["detail"]["engine"] == "pyscenedetect"
    assert versions["detail"]["engineVersion"]
    assert versions["configVersion"].startswith("scene-detect/v1:")
    assert versions["stageVersion"].startswith("npick.stage.scene_detection/v1:")
    # 이 단계는 가중치도 프롬프트도 쓰지 않는다. 키는 남고 값만 비어야 한다.
    assert versions["modelVersion"] is None
    assert versions["promptVersion"] is None


@pytest.mark.asyncio
async def test_round_trip_sends_heartbeats(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    make_video: Any,
) -> None:
    video = make_video("heartbeat", [("bars", 20), ("white", 20)])
    target = media_root / "clips/a/source.mp4"
    target.parent.mkdir(parents=True)
    target.write_bytes(video.read_bytes())
    fake_backend.enqueue_claim(
        make_job(
            inputs={"media": {"storageKey": "clips/a/source.mp4", "transport": "shared-volume"}}
        )
    )

    await _runner(job_client, media_root).run_once()

    assert len(fake_backend.calls("heartbeat")) >= 1


@pytest.mark.asyncio
async def test_claim_declares_the_stage_it_can_run(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """BE 는 이 선언으로 배정을 거른다. pipeline.yml 의 정적 배치 목록을 대신한다."""
    await _runner(job_client, media_root).run_once()

    body = json.loads(fake_backend.calls("claim")[0].content)
    declared = {c["stage"]: c["stageVersion"] for c in body["capabilities"]}
    # `scene_transcript_mapping`·`indexing` 은 모델도 설정도 쓰지 않아 어느 워커에서나
    # 선언된다. 나머지 셋(`vlm_metadata`·`asr`·`text_embedding`)은 모델·런타임이 없으면 빠진다.
    assert set(declared) == {
        "scene_detection",
        "frame_extraction",
        "ocr",
        "scene_transcript_mapping",
        "indexing",
    }
    for stage, version in declared.items():
        assert version.startswith(f"npick.stage.{stage}/v1:")


@pytest.mark.asyncio
async def test_no_assignment_does_not_complete(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    fake_backend.enqueue_empty_claim()
    assert await _runner(job_client, media_root).run_once() is ClaimOutcome.IDLE
    assert fake_backend.calls("complete") == []


@pytest.mark.asyncio
async def test_backpressure_is_not_reported_as_an_empty_queue(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """`retryAfterMs` 는 BE 과부하지 큐가 비었다는 뜻이 아니다 (계약 §4.1).

    둘을 한 값으로 뭉개면 배치 실행이 과부하를 "다 끝났다" 로 읽고 남은 영상을
    색인하지 않은 채 0 으로 끝난다.
    """
    fake_backend.enqueue(
        "claim",
        httpx2.Response(200, json=envelope({"assigned": False, "retryAfterMs": 1000})),
    )

    assert await _runner(job_client, media_root).run_once() is ClaimOutcome.BACKPRESSURE
    assert fake_backend.calls("complete") == []


@pytest.mark.asyncio
async def test_failed_stage_is_not_reported_as_success(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """잡을 받아 반납했다는 것과 단계가 성공했다는 것은 다르다.

    `maxAttempts` 가 1 이라 재시도도 없다. 둘을 뭉개면 시드가 전부 죽어도 배치가
    성공으로 끝난다.
    """
    _plant_default_media(media_root)
    job = make_job()
    job["inputs"]["config"] = {"threshold": 41.0}  # type: ignore[index]  # 거부되는 설정
    fake_backend.enqueue_claim(job=job)

    assert await _runner(job_client, media_root).run_once() is ClaimOutcome.FAILED

    assert fake_backend.body("complete")["status"] == "failed"


# ── 구현되지 않은 단계 ───────────────────────────────────────────────


@pytest.mark.asyncio
async def test_unimplemented_stage_is_skipped_not_failed(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """FRD 표에는 있으나 구현이 없는 단계. 비치명 단계의 생략은 run 을 멈추지 않는다."""
    fake_backend.enqueue_claim(make_job(stage="transcript_selection"))

    await _runner(job_client, media_root).run_once()

    body = _complete_body(fake_backend)
    assert body["status"] == "skipped"
    assert body["error"]["code"] == "NO_ADAPTER"
    assert body["error"]["retryable"] is False


@pytest.mark.asyncio
async def test_unknown_stage_name_is_permanent_failure(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    fake_backend.enqueue_claim(make_job(stage="nope"))

    await _runner(job_client, media_root).run_once()

    body = _complete_body(fake_backend)
    assert body["status"] == "failed"
    assert body["error"]["code"] == "UNSUPPORTED_STAGE"
    assert body["error"]["retryable"] is False


@pytest.mark.asyncio
async def test_failure_still_carries_the_required_version_keys(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    # 봉투는 실패에도 versions 를 요구한다. 없으면 BE 가 거절한다.
    fake_backend.enqueue_claim(make_job(stage="ocr"))
    await _runner(job_client, media_root).run_once()

    versions = _complete_body(fake_backend)["versions"]
    assert versions["stageVersion"]
    assert versions["outputSchemaVersion"]


@pytest.mark.asyncio
async def test_failure_reports_the_schema_this_worker_produces_not_the_assignment(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """같은 잡의 실패와 성공이 같은 출력 형식을 주장해야 한다.

    BE 는 아직 모든 단계에 `.output/v1` 을 배정한다(계약 §11 항목 12). 배정값을 그대로
    실으면 `ocr` 실패는 v1, 성공은 v2 가 되어 두 기록이 서로 다른 형식을 선언한다 —
    나중에 어느 쪽이 그 단계의 출력 형식이었는지 기록만으로는 알 수 없다. 워커가 무엇을
    낼 수 있는지는 워커가 아는 사실이므로 배정이 그것을 덮어쓰지 않는다.
    """
    fake_backend.enqueue_claim(
        make_job(stage="ocr", outputSchemaVersion="npick.stage.ocr.output/v1")
    )
    await _runner(job_client, media_root).run_once()

    body = _complete_body(fake_backend)
    assert body["status"] != "succeeded"
    assert body["versions"]["outputSchemaVersion"] == "npick.stage.ocr.output/v2"


# ── 실패 분류 ────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_missing_input_is_permanent(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    fake_backend.enqueue_claim(
        make_job(inputs={"media": {"storageKey": "clips/a/nope.mp4", "transport": "shared-volume"}})
    )

    await _runner(job_client, media_root).run_once()

    body = _complete_body(fake_backend)
    assert body["error"]["code"] == "MEDIA_UNAVAILABLE"
    assert body["error"]["retryable"] is False


@pytest.mark.asyncio
async def test_broken_video_is_classified_permanent(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """detect_scenes 는 맨 ValueError 를 던진다. 경계에서 영구 오류로 번역한다."""
    target = media_root / "clips/a/source.mp4"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"not a video")
    fake_backend.enqueue_claim(
        make_job(
            inputs={"media": {"storageKey": "clips/a/source.mp4", "transport": "shared-volume"}}
        )
    )

    await _runner(job_client, media_root).run_once()

    body = _complete_body(fake_backend)
    assert body["status"] == "failed"
    # 벤더 예외(VideoOpenFailure)가 어댑터 경계에서 계약 어휘로 번역돼야 한다.
    assert body["error"]["code"] == "UNSUPPORTED_MEDIA"
    assert body["error"]["retryable"] is False


@pytest.mark.asyncio
async def test_error_message_does_not_leak_absolute_paths(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    fake_backend.enqueue_claim(
        make_job(inputs={"media": {"storageKey": "clips/a/nope.mp4", "transport": "shared-volume"}})
    )

    await _runner(job_client, media_root).run_once()

    assert str(media_root) not in _complete_body(fake_backend)["error"]["message"]


# ── lease 와 인증 ────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_lease_lost_during_complete_is_swallowed(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """fencing 으로 거절당하는 것은 정상이다. 예외로 루프를 깨지 않는다."""
    fake_backend.enqueue_claim(make_job(stage="ocr"))
    fake_backend.enqueue_status("complete", 409, code="JOB_409_002")

    await _runner(job_client, media_root).run_once()

    assert len(fake_backend.calls("complete")) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("status,code", [(401, "JOB_401"), (400, "JOB_400_001")])
async def test_run_stops_on_permanent_claim_rejection(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    status: int,
    code: str,
) -> None:
    """A rejected token or malformed claim must not start an endless polling loop."""
    fake_backend.enqueue_status("claim", status, code=code)

    await asyncio.wait_for(_runner(job_client, media_root).run(), timeout=5)

    assert len(fake_backend.calls("claim")) == 1


@pytest.mark.asyncio
async def test_run_survives_a_transport_outage(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """BE 가 잠깐 죽어도 워커는 살아 있어야 한다. 5회 재시도 뒤 401 로 루프를 끝낸다."""
    for _ in range(5):
        fake_backend.enqueue_status("claim", 503)
    fake_backend.enqueue_status("claim", 401, code="JOB_401")

    await asyncio.wait_for(_runner(job_client, media_root).run(), timeout=5)

    assert len(fake_backend.calls("claim")) == 6


@pytest.mark.asyncio
async def test_empty_claim_does_not_sleep(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """대기는 이미 서버가 했다. 여기서 또 자면 유휴 주기가 두 배가 된다."""
    fake_backend.enqueue(
        "claim", httpx2.Response(200, json=envelope({"assigned": False, "retryAfterMs": 0}))
    )
    slept: list[float] = []

    async def record(seconds: float) -> None:
        slept.append(seconds)

    monkeypatch.setattr(asyncio, "sleep", record)
    await _runner(job_client, media_root).run_once()

    assert slept == []


# ── 중단 신호 (계약 §4.2) ────────────────────────────────────────────


@pytest.fixture
def blocking_stage(monkeypatch: pytest.MonkeyPatch) -> threading.Event:
    """heartbeat 가 한 번 올 때까지 멈춰 있는 단계를 등록한다.

    "실행 중에 중단 신호가 왔다" 를 재현해야 하는데, 실제 단계가 언제 끝나는지에
    기대면 테스트가 기계 속도에 흔들린다. 단계를 붙잡아 순서를 고정한다.
    """
    released = threading.Event()

    def run(ctx: StageContext) -> StageOutcome:
        released.wait(timeout=5)
        return StageOutcome(
            output={"ok": True},
            versions=StageVersion(
                stage_version="npick.stage.scene_detection/v1:0badc0de",
                output_schema_version="npick.stage.scene_detection.output/v1",
            ),
        )

    monkeypatch.setattr(
        registry, "HANDLERS", {"scene_detection": StageHandler("scene_detection", run, None)}
    )
    return released


@pytest.mark.asyncio
async def test_abort_discards_the_result(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    blocking_stage: threading.Event,
) -> None:
    """BE 가 중단을 지시하면 결과를 반납하지 않는다.

    계약 §4.2: "abort 를 받은 워커는 즉시 중단하고 산출물을 버리며 complete 를 보내지
    않는다." 여기서 반납하면 BE 가 이미 다른 워커에 재배정한 단계 위에 덮어쓴다.
    """
    _plant_default_media(media_root)
    fake_backend.enqueue_claim()
    fake_backend.enqueue_heartbeat_abort()
    fake_backend.on_request = lambda route, _req: (
        blocking_stage.set() if route == "heartbeat" else None
    )

    await _runner(job_client, media_root).run_once()

    assert fake_backend.calls("complete") == []


@pytest.mark.asyncio
async def test_lease_revoked_during_execution_discards_the_result(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    blocking_stage: threading.Event,
) -> None:
    """heartbeat 가 409 로 회수를 알리면 결과를 반납하지 않는다(fencing)."""
    _plant_default_media(media_root)
    fake_backend.enqueue_claim()
    fake_backend.enqueue_status("heartbeat", 409, code="JOB_409_002")
    fake_backend.on_request = lambda route, _req: (
        blocking_stage.set() if route == "heartbeat" else None
    )

    await _runner(job_client, media_root).run_once()

    assert fake_backend.calls("complete") == []


@pytest.mark.asyncio
async def test_stage_already_completed_is_not_an_error(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """이미 성공한 단계에 대한 409 는 정상이다. 폐기하고 다음으로 간다."""
    fake_backend.enqueue_claim(make_job(stage="ocr"))
    fake_backend.enqueue_status("complete", 409, code="JOB_409_001")

    await _runner(job_client, media_root).run_once()

    assert len(fake_backend.calls("complete")) == 1


# ── next 선배정 (계약 §4.3) ──────────────────────────────────────────


@pytest.mark.asyncio
async def test_next_assignment_is_executed(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """complete 응답의 next 를 이어서 실행한다.

    무시하면 BE 가 배정한 lease 가 실행도 heartbeat 도 없이 만료된다.
    """
    fake_backend.enqueue_claim(make_job(stage="ocr"))
    fake_backend.enqueue_complete_with_next(make_job(stage="asr"))

    await _runner(job_client, media_root).run_once()

    stages = [json.loads(c.content)["stage"] for c in fake_backend.calls("complete")]
    assert stages == ["ocr", "asr"]


@pytest.mark.asyncio
async def test_next_chain_stops_when_not_assigned(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    fake_backend.enqueue_claim(make_job(stage="ocr"))
    fake_backend.enqueue(
        "complete",
        httpx2.Response(200, json=envelope({"accepted": True, "next": {"assigned": False}})),
    )

    await _runner(job_client, media_root).run_once()

    assert len(fake_backend.calls("complete")) == 1


# ── 입력 404 (계약 §9.1 JOB_404_002) ─────────────────────────────────


@pytest.mark.asyncio
async def test_input_404_is_reported_as_media_unavailable(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    """없는 입력을 다시 받아도 없다. 일시 오류로 보고하면 재시도 예산만 태운다."""
    fake_backend.enqueue_claim(
        make_job(inputs={"media": {"storageKey": "clips/a/gone.mp4", "transport": "http"}})
    )
    fake_backend.enqueue_status("artifact_get", 404, code="JOB_404_002")

    await _runner(job_client, None).run_once()

    error = _complete_body(fake_backend)["error"]
    assert error["code"] == "MEDIA_UNAVAILABLE"
    assert error["retryable"] is False


# ── heartbeat 주기 ───────────────────────────────────────────────────


def test_heartbeat_interval_is_capped_by_the_setting(
    job_client: JobApiClient, media_root: Path
) -> None:
    """BE 가 lease TTL 보다 긴 주기를 주면 lease 가 만료된다. 설정이 상한이다.

    README·settings 가 이 값을 "상한" 이라고 설명한다. 그 설명이 참인지 확인한다.
    """
    runner = _runner(job_client, media_root)  # heartbeat_seconds=0.01
    lease = LeaseGrant.model_validate(make_lease(heartbeatIntervalMs=120_000))

    assert runner._heartbeat_interval(lease) == 0.01


def test_heartbeat_interval_follows_the_server_when_below_the_cap(
    job_client: JobApiClient, media_root: Path
) -> None:
    """상한 아래면 서버가 준 값을 그대로 쓴다."""
    runner = _runner(job_client, media_root)
    lease = LeaseGrant.model_validate(make_lease(heartbeatIntervalMs=5))

    assert runner._heartbeat_interval(lease) == 0.005


# ── 루프 생존 ────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_run_survives_an_unexpected_error(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """계약을 벗어난 응답에 루프가 죽으면 안 된다.

    죽으면 lifespan 태스크가 조용히 끝나고 아무도 await 하지 않아 예외가 어디에도
    보고되지 않는다. 컨테이너는 healthy 인데 잡을 하나도 안 가져간다.
    """
    # assigned=true 인데 job·lease 가 없다 — ClaimResponse 검증이 터진다.
    fake_backend.enqueue("claim", httpx2.Response(200, json=envelope({"assigned": "네"})))
    fake_backend.enqueue_status("claim", 401, code="JOB_401")

    await asyncio.wait_for(_runner(job_client, media_root).run(), timeout=5)

    # 두 번째 claim 이 있었다는 것이 루프가 살아남았다는 증거다.
    assert len(fake_backend.calls("claim")) == 2


# ── heartbeat 가 태스크 밖으로 예외를 내보내지 않는다 ────────────────
# heartbeat 태스크가 예외를 든 채 끝나면 이미 done 이라 cancel() 이 무효이고,
# join 이 그 예외를 finally 안에서 되던진다 — _execute 가 정상 결과를 만든 뒤다.
# contextlib.suppress(CancelledError) 로는 막히지 않는다.


@pytest.mark.asyncio
async def test_heartbeat_403_does_not_stop_the_worker(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """heartbeat 의 403 하나가 폴링을 영구 정지시키면 안 된다.

    run() 은 JobApiUnauthorizedError 를 "폴링을 멈춘다" 로 처리한다. heartbeat 예외가
    거기까지 올라가면 워커는 영원히 놀고 /health 는 계속 ok 를 준다.
    """
    _plant_default_media(media_root)
    fake_backend.enqueue_claim()
    fake_backend.enqueue_status("heartbeat", 403, code="JOB_403_002")
    runner = _runner(job_client, media_root)

    assert await runner.run_once() is ClaimOutcome.FAILED

    assert fake_backend.calls("complete") == []


@pytest.mark.asyncio
async def test_heartbeat_409_001_discards_the_result(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """이미 완료된 단계다. 계약 §4.2 가 STAGE_ALREADY_COMPLETED 를 정당한 abort 사유로
    열거하므로 예상 밖 상황이 아니다 — 조용히 폐기하고 루프는 계속한다."""
    _plant_default_media(media_root)
    fake_backend.enqueue_claim()
    fake_backend.enqueue_status("heartbeat", 409, code="JOB_409_001")

    assert await _runner(job_client, media_root).run_once() is ClaimOutcome.FAILED

    assert fake_backend.calls("complete") == []


@pytest.mark.asyncio
async def test_heartbeat_conflict_discards_the_result(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """모르는 충돌·run 없음도 결과를 버릴 사유다. 루프 밖으로 나가면 안 된다."""
    _plant_default_media(media_root)
    fake_backend.enqueue_claim()
    fake_backend.enqueue_status("heartbeat", 404, code="JOB_404_001")

    assert await _runner(job_client, media_root).run_once() is ClaimOutcome.FAILED

    assert fake_backend.calls("complete") == []


@pytest.mark.asyncio
async def test_lease_lost_during_execution_is_not_masked_by_the_join(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """finally 에서 예외가 올라오면 try 에서 나온 LeaseLostError 를 가린다.

    가려지면 "lease 를 잃어 결과를 버린다" 대신 엉뚱한 오류가 run() 까지 간다.
    """
    _plant_default_media(media_root)
    fake_backend.enqueue_claim()
    # 실행 중 lease 회수 + heartbeat 도 실패한다. 둘이 겹쳐도 폐기 경로로 가야 한다.
    fake_backend.enqueue_status("heartbeat", 409, code="JOB_409_002")

    assert await _runner(job_client, media_root).run_once() is ClaimOutcome.FAILED

    assert fake_backend.calls("complete") == []


@pytest.mark.asyncio
async def test_heartbeat_stays_alive_while_complete_retries(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """반납 도중에도 lease 를 연장해야 한다.

    _send 는 complete 에 5회 시도를 주고 최악 ~165초다. lease TTL 은 60초이고
    계약 §4.2 가 "연장하는 것은 heartbeat 뿐" 이므로, 그 구간에 heartbeat 가
    없으면 반납 도중 lease 가 만료된다.
    """
    _plant_default_media(media_root)
    fake_backend.enqueue_claim()
    fake_backend.enqueue_status("complete", 503)
    fake_backend.enqueue_status("complete", 503)
    # 재시도 사이에 heartbeat 가 뛰도록 강제한다.
    seen: list[str] = []
    fake_backend.on_request = lambda route, request: seen.append(route)

    # 입력이 placeholder 바이트라 단계 자체는 실패한다. 관심사는 그게 아니라
    # 반납이 재시도되는 동안 heartbeat 가 뛰었는가다.
    assert await _runner(job_client, media_root).run_once() is ClaimOutcome.FAILED

    assert len(fake_backend.calls("complete")) == 3
    # complete 첫 시도 뒤에 heartbeat 가 한 번이라도 있었는가.
    first_complete = seen.index("complete")
    assert "heartbeat" in seen[first_complete:]


# ── 유휴 claim 루프 하한 ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_envelope_failure_does_not_spin(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """200 {"isSuccess": false} 에는 클라이언트 백오프가 없다.

    _unwrap 은 그 응답을 재시도도 수면도 없이 JobApiUnavailableError 로 올린다
    (HTTP 1회, sleep 0회). run() 도 자지 않으면 워커가 /claim 을 무제한 두드린다.
    잡 API 가 아직 구현되지 않았으므로 개발 중 흔한 상태다.
    """
    slept: list[float] = []
    real_sleep = asyncio.sleep

    async def record(seconds: float) -> None:
        slept.append(seconds)
        await real_sleep(0)

    fake_backend.enqueue("claim", httpx2.Response(200, json=envelope(None, is_success=False)))
    fake_backend.enqueue_status("claim", 401, code="JOB_401")

    runner = _runner(job_client, media_root)
    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(asyncio, "sleep", record)
        await asyncio.wait_for(runner.run(), timeout=5)

    assert len(fake_backend.calls("claim")) == 2
    assert slept, "봉투 실패 뒤에 최소 수면이 없다"


@pytest.mark.asyncio
async def test_non_empty_config_is_rejected(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """실제로 쓰는 단계가 없는데 조용히 무시하면 버전 기록이 거짓이 된다.

    stageVersion 은 기본 설정에서 계산된다. BE 가 inputs.config 를 보내고 워커가
    무시하면, 보고되는 stageVersion·configVersion 이 "이 설정으로 만든 결과" 라는
    거짓 기록이 된다 — 계약 §7 이 막으려는 상황이다.
    """
    _plant_default_media(media_root)
    job = make_job()
    job["inputs"]["config"] = {"threshold": 41.0}  # type: ignore[index]
    fake_backend.enqueue_claim(job=job)

    assert await _runner(job_client, media_root).run_once() is ClaimOutcome.FAILED

    body = fake_backend.body("complete")
    assert body["status"] == "failed"
    assert body["error"]["code"] == "VALIDATION_ERROR"
    assert body["error"]["retryable"] is False


# ── frame_extraction: 산출물 업로드까지 ─────────────────────────────────
# 이 단계는 payload 만 반납하는 앞 단계와 달리 **파일을 올린다.** 그 경로가 계약
# §4.4·§5 대로 도는지, 그리고 lease·중단 규칙을 지키는지가 아래 관심사다.

#: 합성 영상 두 블록에 대응하는 상류 산출물. BE 가 되돌려 주는 모양 그대로다.
UPSTREAM_TWO_SCENES: dict[str, Any] = {
    "sceneDetection": {
        "scenes": [
            {"sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 2000},
            {"sceneIndex": 1, "startTimeMs": 2000, "endTimeMs": 4000},
        ],
        "mediaDurationMs": 4000,
        "frameRate": 10.0,
    }
}

FRAME_PREFIX = f"runs/{RUN_ID}/frame_extraction/a1/"


def _frame_job(storage_key: str, upstream: dict[str, Any] | None) -> dict[str, Any]:
    inputs: dict[str, Any] = {"media": {"storageKey": storage_key, "transport": "shared-volume"}}
    if upstream is not None:
        inputs["upstream"] = upstream
    return make_job(
        stage="frame_extraction",
        idempotencyKey=f"{RUN_ID}:frame_extraction:1",
        outputKeyPrefix=FRAME_PREFIX,
        inputs=inputs,
    )


def _plant_video(media_root: Path, video: Path, storage_key: str) -> None:
    target = media_root / storage_key
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(video.read_bytes())


def _uploaded_keys(fake_backend: FakeBackend) -> set[str]:
    return {
        request.url.path.split("/artifacts/")[1] for request in fake_backend.calls("artifact_put")
    }


@pytest.mark.asyncio
async def test_round_trip_frame_extraction_job(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    make_video: Any,
) -> None:
    """실제 영상 → 실제 keyframe 추출 → JPEG 업로드 → 계약 봉투."""
    _plant_video(
        media_root, make_video("frames-rt", [("bars", 20), ("noise", 20)]), "clips/a/source.mp4"
    )
    fake_backend.enqueue_claim(_frame_job("clips/a/source.mp4", UPSTREAM_TWO_SCENES))

    assert await _runner(job_client, media_root).run_once() is ClaimOutcome.SUCCEEDED

    body = _complete_body(fake_backend)
    assert body["stage"] == "frame_extraction"
    assert body["status"] == "succeeded"
    scenes = body["output"]["scenes"]
    assert [scene["sceneIndex"] for scene in scenes] == [0, 1]
    for scene in scenes:
        # FRD F-03 은 "장면의 **복수** 키프레임" 을 요구한다(docs/frd.md:121).
        assert len(scene["keyframes"]) >= 2
        # 대표는 목록의 첫 장이다. BE 는 이 순서대로 INSERT 한다.
        assert scene["keyframes"][0]["timestampMs"] == scene["representativeTimestampMs"]
        for keyframe in scene["keyframes"]:
            assert keyframe["sceneIndex"] == scene["sceneIndex"]
            assert keyframe["storageKey"].startswith(FRAME_PREFIX)


@pytest.mark.asyncio
async def test_frame_extraction_uploads_every_keyframe_before_completing(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    make_video: Any,
) -> None:
    """반납 뒤로 미루면 BE 가 keyframe 행을 만든 뒤에 파일이 올라간다.

    그 사이 조회는 없는 파일을 가리킨다. 그래서 순서가 계약이다.
    """
    _plant_video(
        media_root,
        make_video("frames-order", [("bars", 20), ("noise", 20)]),
        "clips/a/source.mp4",
    )
    fake_backend.enqueue_claim(_frame_job("clips/a/source.mp4", UPSTREAM_TWO_SCENES))

    await _runner(job_client, media_root).run_once()

    routes = [FakeBackend._route(request) for request in fake_backend.requests]
    assert "artifact_put" in routes
    last_upload = max(index for index, route in enumerate(routes) if route == "artifact_put")
    assert routes.index("complete") > last_upload

    body = _complete_body(fake_backend)
    declared = {
        keyframe["storageKey"]
        for scene in body["output"]["scenes"]
        for keyframe in scene["keyframes"]
    }
    # payload 가 가리키는 키와 실제로 올라간 키가 하나라도 다르면 근거 프레임이 깨진다.
    assert _uploaded_keys(fake_backend) == declared
    assert {artifact["storageKey"] for artifact in body["artifacts"]} == declared
    # `keyframe.storage_key` 와 같은 어휘를 쓴다. 새 식별자를 만들지 않는다(계약 §4.4).
    assert {artifact["kind"] for artifact in body["artifacts"]} == {"keyframe"}


@pytest.mark.asyncio
async def test_frame_extraction_upload_declares_the_content_hash(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    make_video: Any,
) -> None:
    """계약 §4.4 의 `X-Content-SHA256`. 본문과 다르면 BE 가 JOB_400_002 로 거절한다."""
    _plant_video(
        media_root, make_video("frames-hash", [("bars", 20), ("noise", 20)]), "clips/a/source.mp4"
    )
    fake_backend.enqueue_claim(_frame_job("clips/a/source.mp4", UPSTREAM_TWO_SCENES))

    await _runner(job_client, media_root).run_once()

    assert fake_backend.calls("artifact_put")
    for request in fake_backend.calls("artifact_put"):
        assert request.headers["content-type"] == "image/jpeg"
        assert request.headers["x-content-sha256"] == hashlib.sha256(request.content).hexdigest()
        assert request.content.startswith(JPEG_MAGIC)


@pytest.mark.asyncio
async def test_missing_upstream_is_a_permanent_failure(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    make_video: Any,
) -> None:
    """상류 산출물이 없으면 이 단계는 할 일을 모른다. 빈 결과를 내지 않는다.

    영구로 신고하는 이유는 BE 가 다시 보내도 같은 것을 보내기 때문이다. 일시로 두면
    maxAttempts 만큼 태우고 같은 자리에서 죽는다.
    """
    _plant_video(media_root, make_video("frames-noup", [("bars", 20)]), "clips/a/source.mp4")
    fake_backend.enqueue_claim(_frame_job("clips/a/source.mp4", None))

    await _runner(job_client, media_root).run_once()

    body = _complete_body(fake_backend)
    assert body["status"] == "failed"
    assert body["error"]["code"] == "VALIDATION_ERROR"
    assert body["error"]["retryable"] is False
    assert not fake_backend.calls("artifact_put")


@pytest.mark.asyncio
async def test_abandoned_frame_extraction_does_not_upload(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    make_video: Any,
) -> None:
    """버릴 결과의 바이트를 네트워크로 보내지 않는다."""
    _plant_video(
        media_root,
        make_video("frames-abort", [("bars", 20), ("noise", 20)]),
        "clips/a/source.mp4",
    )
    fake_backend.enqueue_claim(_frame_job("clips/a/source.mp4", UPSTREAM_TWO_SCENES))
    fake_backend.enqueue_heartbeat_abort()

    await _runner(job_client, media_root).run_once()

    assert not fake_backend.calls("artifact_put")
    assert not fake_backend.calls("complete")


@pytest.mark.asyncio
async def test_abort_during_upload_stops_the_remaining_files(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """중단은 **업로드 도중에도** 온다. 진입 전에 한 번만 보면 남은 파일을 다 보낸다.

    결과는 어차피 버려지지만(`run_once` 가 폐기한다) 장면 수백 장이면 그 전송이 통째로
    헛일이고, 그동안 이 워커는 다음 잡을 잡지 못한다. 계약 §4.2 는 abort 를 받은 워커가
    즉시 멈추기를 요구한다.
    """
    uploads = []
    for index in range(3):
        local = tmp_path / f"kf-{index}.jpg"
        local.write_bytes(JPEG_MAGIC + f" {index}".encode())
        uploads.append(
            PendingUpload(
                ref=ArtifactRef(
                    kind="keyframe",
                    storage_key=f"{FRAME_PREFIX}s0000/kf-{index}.jpg",
                    byte_size=local.stat().st_size,
                    content_hash=hashlib.sha256(local.read_bytes()).hexdigest(),
                ),
                local_path=local,
                content_type="image/jpeg",
            )
        )

    control = _JobControl(abandoned=asyncio.Event())
    real_upload = job_client.upload_artifact

    async def abandon_while_uploading(*args: Any, **kwargs: Any) -> None:
        """첫 파일을 보내는 동안 heartbeat 가 중단을 알린 상황."""
        control.abandon("RUN_CANCELLED")
        await real_upload(*args, **kwargs)

    monkeypatch.setattr(job_client, "upload_artifact", abandon_while_uploading)

    runner = _runner(job_client, media_root)
    outcome = StageOutcome(
        output={"scenes": []},
        versions=StageVersion(
            stage_version="npick.stage.frame_extraction/v1:0badc0de",
            output_schema_version="npick.stage.frame_extraction.output/v1",
        ),
        uploads=tuple(uploads),
    )
    job = JobAssignment.model_validate(_frame_job("clips/a/source.mp4", UPSTREAM_TWO_SCENES))

    refs = await runner._upload(job, outcome, control, tmp_path)

    assert refs == ()
    assert len(fake_backend.calls("artifact_put")) == 1


@pytest.mark.asyncio
async def test_uploaded_keyframes_are_removed_from_the_pod_disk(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    tmp_path: Path,
) -> None:
    """올린 파일은 남기지 않는다. 파드 디스크는 휘발성이고 용량 제한이 있다.

    피크가 내려가는 것은 아니다 — 단계가 그 클립의 keyframe 전부를 쓴 뒤에야 업로드가
    시작한다. 여기서 잠그는 것은 뒤쪽 파일을 올리는 동안 앞쪽이 디스크를 잡고 있지
    않다는 것이다.
    """
    uploads = []
    for index in range(3):
        local = tmp_path / f"kf-{index}.jpg"
        local.write_bytes(JPEG_MAGIC + f" {index}".encode())
        uploads.append(
            PendingUpload(
                ref=ArtifactRef(
                    kind="keyframe",
                    storage_key=f"{FRAME_PREFIX}s0000/kf-{index}.jpg",
                    byte_size=local.stat().st_size,
                    content_hash=hashlib.sha256(local.read_bytes()).hexdigest(),
                ),
                local_path=local,
                content_type="image/jpeg",
            )
        )

    runner = _runner(job_client, media_root)
    outcome = StageOutcome(
        output={"scenes": []},
        versions=StageVersion(
            stage_version="npick.stage.frame_extraction/v1:0badc0de",
            output_schema_version="npick.stage.frame_extraction.output/v1",
        ),
        uploads=tuple(uploads),
    )
    job = JobAssignment.model_validate(_frame_job("clips/a/source.mp4", UPSTREAM_TWO_SCENES))

    refs = await runner._upload(job, outcome, _JobControl(abandoned=asyncio.Event()), tmp_path)

    assert len(refs) == 3
    assert len(fake_backend.calls("artifact_put")) == 3
    assert [upload.local_path.exists() for upload in uploads] == [False, False, False]


@pytest.mark.asyncio
async def test_upload_failure_is_reported_as_a_transient_stage_failure(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    make_video: Any,
) -> None:
    """계약 §9.2 의 `ARTIFACT_UPLOAD_FAILED` 는 일시다. 다시 올리면 성공할 수 있다."""
    _plant_video(
        media_root, make_video("frames-503", [("bars", 20), ("noise", 20)]), "clips/a/source.mp4"
    )
    fake_backend.enqueue_claim(_frame_job("clips/a/source.mp4", UPSTREAM_TWO_SCENES))
    for _ in range(5):  # client 의 max_attempts 를 소진시킨다
        fake_backend.enqueue_status("artifact_put", 503)

    await _runner(job_client, media_root).run_once()

    body = _complete_body(fake_backend)
    assert body["status"] == "failed"
    assert body["error"]["code"] == "ARTIFACT_UPLOAD_FAILED"
    assert body["error"]["retryable"] is True


@pytest.mark.asyncio
async def test_output_key_outside_the_prefix_never_leaves_the_worker(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """BE 도 JOB_403_001 로 막지만 그건 첫 파일을 이미 보낸 뒤다.

    접두 밖 키는 재시도가 고치지 못하는 워커 버그이므로 영구로 신고한다. 권한 오류로
    다루면 워커 루프 전체가 멈춘다.
    """
    _plant_default_media(media_root)
    stray = tmp_path / "stray.jpg"
    stray.write_bytes(JPEG_MAGIC + b" nope")

    def run(ctx: StageContext) -> StageOutcome:
        return StageOutcome(
            output={"scenes": []},
            versions=StageVersion(
                stage_version="npick.stage.frame_extraction/v1:0badc0de",
                output_schema_version="npick.stage.frame_extraction.output/v1",
            ),
            uploads=(
                PendingUpload(
                    ref=ArtifactRef(
                        kind="keyframe",
                        storage_key="runs/other-run/frame_extraction/a1/s0000/kf.jpg",
                        byte_size=stray.stat().st_size,
                        content_hash=hashlib.sha256(stray.read_bytes()).hexdigest(),
                    ),
                    local_path=stray,
                    content_type="image/jpeg",
                ),
            ),
        )

    monkeypatch.setattr(
        registry, "HANDLERS", {"frame_extraction": StageHandler("frame_extraction", run, None)}
    )
    fake_backend.enqueue_claim(_frame_job("clips/398021840012345/source.mp4", UPSTREAM_TWO_SCENES))

    await _runner(job_client, media_root).run_once()

    assert not fake_backend.calls("artifact_put")
    body = _complete_body(fake_backend)
    assert body["status"] == "failed"
    assert body["error"]["code"] == "VALIDATION_ERROR"
    assert body["error"]["retryable"] is False


@pytest.mark.asyncio
async def test_unopenable_media_is_a_permanent_failure(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """벤더 예외를 어댑터 경계에서 번역한다. 같은 파일은 다시 열어도 안 열린다.

    번역하지 않고 통과시키면 분류를 못 해 "열 수 없는 파일" 이 재시도 가능으로
    보고되고, maxAttempts 만큼 태우고 같은 자리에서 죽는다.
    """
    _plant_default_media(media_root)  # mp4 가 아닌 바이트를 심는다
    fake_backend.enqueue_claim(_frame_job("clips/398021840012345/source.mp4", UPSTREAM_TWO_SCENES))

    await _runner(job_client, media_root).run_once()

    body = _complete_body(fake_backend)
    assert body["status"] == "failed"
    assert body["error"]["code"] == "UNSUPPORTED_MEDIA"
    assert body["error"]["retryable"] is False
    assert not fake_backend.calls("artifact_put")


# ── 상류 산출물 입력 (ocr) ──────────────────────────────────────────


def _ocr_job(**overrides: object) -> dict[str, object]:
    """`ocr` 배정. 상류 keyframe 한 장을 인라인으로 되돌려 준다."""
    job = make_job(
        stage="ocr",
        idempotencyKey=f"{RUN_ID}:ocr:1",
        outputKeyPrefix=f"runs/{RUN_ID}/ocr/a1/",
    )
    job["inputs"] = {
        "media": {
            "storageKey": "clips/398021840012345/source.mp4",
            "transport": "shared-volume",
            "localPath": "clips/398021840012345/source.mp4",
        },
        "upstream": {
            "frameExtraction": {
                "scenes": [
                    {
                        "sceneIndex": 0,
                        "keyframes": [
                            {
                                "sceneIndex": 0,
                                "timestampMs": 4200,
                                "storageKey": KEYFRAME_KEY,
                            }
                        ],
                    }
                ],
                "imageWidth": 1920,
                "imageHeight": 1080,
            }
        },
    }
    job.update(overrides)
    return job


KEYFRAME_KEY = f"runs/{RUN_ID}/frame_extraction/a1/s0000/kf-000004200.jpg"


@pytest.mark.asyncio
async def test_ocr_v2_uploads_replayable_result_before_complete(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """실제 OCR 핸들러·러너의 JSON 보존 경로. 엔진과 BE만 fake다."""
    import npick_worker.ocr as ocr_module
    from npick_worker.jobs.models import OcrOutput
    from npick_worker.ocr import KeyframeRef, TextDetection, to_observations
    from npick_worker.ocr.models import OcrResult

    def fake_read(*args: object, **kwargs: object) -> OcrResult:
        return OcrResult(
            keyframes=(
                to_observations(
                    KeyframeRef(0, 4200, KEYFRAME_KEY),
                    [
                        TextDetection(
                            "  원문 보존  ", 0.5, ((0.0, 0.0), (10.0, 0.0), (10.0, 4.0), (0.0, 4.0))
                        )
                    ],
                    min_confidence=0.7,
                ),
            ),
            config_version="ocr/v1:test",
            engine="fake",
            engine_version="1",
            tokenizer="fake/v1",
            min_confidence=0.7,
        )

    monkeypatch.setattr(ocr_module, "read_keyframes", fake_read)
    image = media_root / KEYFRAME_KEY
    image.parent.mkdir(parents=True, exist_ok=True)
    image.write_bytes(JPEG_MAGIC)
    # BE 가 실제로 싣는 값이다 — `StageExecutionService` 는 모든 단계에
    # `PipelineStages.outputSchema()` = `.../output/v1` 를 넣는다. 배정을 비워 두면
    # 러너가 제 기본값으로 메워서 아래 어긋남이 테스트에서 사라진다.
    fake_backend.enqueue_claim(_ocr_job(outputSchemaVersion="npick.stage.ocr.output/v1"))
    await _runner(job_client, media_root).run_once()

    body = _complete_body(fake_backend)
    assert body["status"] == "succeeded"
    # **배정은 v1 인데 봉투는 v2 다.** BE 는 이 둘을 동등 비교해 성공 complete 를
    # `INVALID_OUTPUT` 으로 거부한다(계약 §11 item 12 의 미해결 항목). fake backend 는
    # 봉투를 검증하지 않으므로 단언을 걸어 두지 않으면 이 어긋남이 드러나지 않는다.
    # 워커를 v1 로 되돌리든 BE 가 단계별 스키마를 읽든, 정리되는 순간 여기가 깨진다.
    assert body["versions"]["outputSchemaVersion"] == "npick.stage.ocr.output/v2"
    uploads = fake_backend.calls("artifact_put")
    assert len(uploads) == 1
    saved = uploads[0].content
    document = json.loads(saved)
    assert document["output"] == body["output"]
    assert document["outputSchemaVersion"] == "npick.stage.ocr.output/v2"
    assert body["artifacts"][0]["contentHash"] == hashlib.sha256(saved).hexdigest()
    assert body["artifacts"][0]["byteSize"] == len(saved)
    assert body["artifacts"][0]["kind"] == "ocr_result"
    assert uploads[0].headers["content-type"] == "application/json"
    output = OcrOutput.model_validate(document["output"])
    original = output.observations[output.text_groups[0].representative_index]
    assert original.raw_text == "  원문 보존  "
    assert original.unverified
    routes = [FakeBackend._route(request) for request in fake_backend.requests]
    assert routes.index("artifact_put") < routes.index("complete")


def _recording_handler(
    seen: dict[str, Path], contents: dict[str, bytes] | None = None
) -> StageHandler:
    """단계가 실제로 받은 `upstream_files` 를 기록한다. 모델을 돌리지 않는다.

    바이트를 단계 안에서 읽는 이유는 작업 디렉터리가 잡이 끝나면 지워지기 때문이다.
    경로만 들고 나가면 테스트가 이미 없는 파일을 열게 된다.
    """

    def run(ctx: StageContext) -> StageOutcome:
        seen.update(ctx.upstream_files)
        if contents is not None:
            contents.update({key: path.read_bytes() for key, path in ctx.upstream_files.items()})
        return StageOutcome(
            output={"observations": [], "keyframesRead": 0, "minConfidence": 0.7},
            versions=StageVersion(
                stage_version="npick.stage.ocr/v1:test",
                # 실제 ocr 핸들러가 내는 것과 같은 스키마여야 한다. 여기만 v1 로 남으면
                # 상류 입력 테스트가 진짜 핸들러와 다른 봉투를 보게 된다.
                output_schema_version="npick.stage.ocr.output/v2",
            ),
        )

    return StageHandler(
        "ocr",
        run,
        required_inputs=registry.HANDLERS["ocr"].required_inputs,
        # 실제 등록과 같아야 한다. 여기서 어긋나면 러너가 영상을 받는지 여부를
        # 테스트가 다르게 보게 된다.
        needs_video=registry.HANDLERS["ocr"].needs_video,
    )


@pytest.mark.asyncio
async def test_upstream_artifacts_come_from_the_shared_mount_without_copying(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    media_root: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """keyframe 은 장면 수백 개에 장 수를 곱한 만큼이다. 같은 볼륨에 있으면 복사하지 않는다."""
    _plant_default_media(media_root)
    planted = media_root / KEYFRAME_KEY
    planted.parent.mkdir(parents=True, exist_ok=True)
    planted.write_bytes(JPEG_MAGIC)

    seen: dict[str, Path] = {}
    monkeypatch.setattr(registry, "HANDLERS", {"ocr": _recording_handler(seen)})
    fake_backend.enqueue_claim(_ocr_job())

    await _runner(job_client, media_root).run_once()

    assert seen[KEYFRAME_KEY] == planted.resolve()
    assert fake_backend.calls("artifact_get") == []
    assert _complete_body(fake_backend)["status"] == "succeeded"


@pytest.mark.asyncio
async def test_upstream_artifacts_are_downloaded_when_there_is_no_mount(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """RunPod 파드에는 공유 볼륨이 없다. 계약 §5 의 http 경로다."""
    seen: dict[str, Path] = {}
    contents: dict[str, bytes] = {}
    monkeypatch.setattr(registry, "HANDLERS", {"ocr": _recording_handler(seen, contents)})

    job = _ocr_job()
    inputs = job["inputs"]
    assert isinstance(inputs, dict)
    inputs["media"] = {
        "storageKey": "clips/398021840012345/source.mp4",
        "transport": "http",
    }
    fake_backend.enqueue_claim(job)
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=JPEG_MAGIC))

    await _runner(job_client, None).run_once()

    downloaded = [request.url.params.get("key") for request in fake_backend.calls("artifact_get")]
    assert KEYFRAME_KEY in downloaded
    assert contents[KEYFRAME_KEY] == JPEG_MAGIC
    # 잡이 끝나면 작업 디렉터리와 함께 지워진다 — 파드 디스크는 휘발성인데 한 파드가
    # 잡을 여러 개 처리하므로 keyframe 수백 장이 남으면 금방 찬다.
    assert not seen[KEYFRAME_KEY].exists()


@pytest.mark.asyncio
async def test_a_stage_that_does_not_need_the_video_never_fetches_it(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`ocr` 은 상류 keyframe 만 읽는다. 원본 영상을 받는 것은 순수한 낭비다.

    `transport: "http"` 에서 이것이 드러난다 — 공유 볼륨이면 어차피 복사가 없지만
    RunPod 파드는 http 이고(계약 §5), 거기서는 700MB 를 받아서 열지도 않고 버린다.
    """
    seen: dict[str, Path] = {}
    monkeypatch.setattr(registry, "HANDLERS", {"ocr": _recording_handler(seen)})

    job = _ocr_job()
    inputs = job["inputs"]
    assert isinstance(inputs, dict)
    media_key = "clips/398021840012345/source.mp4"
    inputs["media"] = {"storageKey": media_key, "transport": "http"}
    fake_backend.enqueue_claim(job)
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=JPEG_MAGIC))

    await _runner(job_client, None).run_once()

    downloaded = [request.url.params.get("key") for request in fake_backend.calls("artifact_get")]
    assert downloaded == [KEYFRAME_KEY]
    assert media_key not in downloaded
    assert _complete_body(fake_backend)["status"] == "succeeded"
