"""claim → 실행 → complete 왕복.

헤드라인 테스트는 실제 mp4 를 실제 `detect_scenes` 로 처리해 가짜 BE 까지 보낸다.
BE 잡 API 는 아직 구현되지 않았으므로 상대는 계약 모양으로만 응답하는 fake 다.
"""

import asyncio
import json
import threading
from pathlib import Path
from typing import Any

import httpx2
import pytest

from npick_worker.jobs import registry
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.media import MediaResolver
from npick_worker.jobs.models import LeaseGrant, WorkerDevice
from npick_worker.jobs.registry import StageContext, StageHandler, StageOutcome
from npick_worker.jobs.runner import JobRunner
from npick_worker.jobs.versions import StageVersion

from .conftest import FakeBackend, envelope, make_job, make_lease

RUN_ID = "398021847361024"

#: 10fps 합성 영상. 20프레임 블록 하나가 2000ms 다.
ROUNDTRIP_BLOCKS = [("bars", 20), ("white", 20), ("noise", 20)]


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

    assert await _runner(job_client, media_root).run_once() is True

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
    stages = {c["stage"] for c in body["capabilities"]}
    assert stages == {"scene_detection"}
    assert body["capabilities"][0]["stageVersion"].startswith("npick.stage.scene_detection/v1:")


@pytest.mark.asyncio
async def test_no_assignment_does_not_complete(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    fake_backend.enqueue_empty_claim()
    assert await _runner(job_client, media_root).run_once() is False
    assert fake_backend.calls("complete") == []


# ── 구현되지 않은 단계 ───────────────────────────────────────────────


@pytest.mark.asyncio
async def test_unimplemented_stage_is_skipped_not_failed(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """FRD 표에는 있으나 구현이 없는 단계. 비치명 단계의 생략은 run 을 멈추지 않는다."""
    fake_backend.enqueue_claim(make_job(stage="ocr"))

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
async def test_run_stops_on_unauthorized(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """토큰이 거절되는데 계속 두드려도 열리지 않는다."""
    fake_backend.enqueue_status("claim", 401, code="JOB_401")

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

    assert await runner.run_once() is True

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

    assert await _runner(job_client, media_root).run_once() is True

    assert fake_backend.calls("complete") == []


@pytest.mark.asyncio
async def test_heartbeat_conflict_discards_the_result(
    job_client: JobApiClient, fake_backend: FakeBackend, media_root: Path
) -> None:
    """모르는 충돌·run 없음도 결과를 버릴 사유다. 루프 밖으로 나가면 안 된다."""
    _plant_default_media(media_root)
    fake_backend.enqueue_claim()
    fake_backend.enqueue_status("heartbeat", 404, code="JOB_404_001")

    assert await _runner(job_client, media_root).run_once() is True

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

    assert await _runner(job_client, media_root).run_once() is True

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

    assert await _runner(job_client, media_root).run_once() is True

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

    assert await _runner(job_client, media_root).run_once() is True

    body = fake_backend.body("complete")
    assert body["status"] == "failed"
    assert body["error"]["code"] == "VALIDATION_ERROR"
    assert body["error"]["retryable"] is False
