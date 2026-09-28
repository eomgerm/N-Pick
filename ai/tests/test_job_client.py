"""잡 API 클라이언트. 계약은 docs/contracts/job-api.md 다.

기대값(경로·헤더 이름·상태 코드)은 계약에서 전사한다. 클라이언트 상수를 import 하면
계약이 바뀐 것을 잡지 못한다.
"""

import asyncio
import json
import logging
from pathlib import Path
from typing import Any

import httpx2
import pytest

from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import (
    ArtifactHashMismatchError,
    ArtifactKeyRejectedError,
    InputDownloadError,
    InputUnavailableError,
    JobApiConflictError,
    JobApiInvalidRequestError,
    JobApiUnauthorizedError,
    JobApiUnavailableError,
    LeaseLostError,
    StageAlreadyCompletedError,
)
from npick_worker.jobs.models import (
    ClaimRequest,
    HeartbeatRequest,
    StageCapability,
    WorkerDevice,
    WorkerIdentity,
)

from .conftest import BreakingStream, FakeBackend, envelope, make_job

#: 계약이 정한 베이스 경로.
CLAIM_PATH = "/api/v1/internal/jobs/claim"
HEARTBEAT_PATH = "/api/v1/internal/jobs/398021847361024/stages/scene_detection/heartbeat"
COMPLETE_PATH = "/api/v1/internal/jobs/398021847361024/stages/scene_detection/complete"

POLL_WAIT_SECONDS = 25


def _claim_request() -> ClaimRequest:
    return ClaimRequest(
        worker=WorkerIdentity(
            worker_id="test-worker",
            instance_id="instance-1",
            fleet="local",
            worker_version="0.1.0",
            max_concurrent_stages=1,
            shared_media_volume=True,
        ),
        capabilities=[
            StageCapability(stage="scene_detection", stage_version="npick.stage.x/v1:0badc0de")
        ],
        device=WorkerDevice(kind="cpu"),
        wait_seconds=POLL_WAIT_SECONDS,
    )


@pytest.fixture(autouse=True)
def _no_real_sleep(monkeypatch: pytest.MonkeyPatch) -> list[float]:
    """백오프를 실제로 자면 테스트가 분 단위로 늘어난다. 잔 시간만 기록한다."""
    slept: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        slept.append(seconds)

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)
    return slept


# ── claim ────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_invalid_request_is_permanent_and_not_retried(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    fake_backend.enqueue_status("claim", 400, code="JOB_400_001")
    with pytest.raises(JobApiInvalidRequestError) as failure:
        await job_client.claim(_claim_request())
    assert failure.value.retryable is False
    assert len(fake_backend.calls("claim")) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("recovers", [True, False])
async def test_hash_rejection_has_one_transport_retry(
    job_client: JobApiClient, fake_backend: FakeBackend, recovers: bool
) -> None:
    fake_backend.enqueue_status("artifact_put", 400, code="JOB_400_002")
    if not recovers:
        fake_backend.enqueue_status("artifact_put", 400, code="JOB_400_002")

    async def send() -> None:
        await job_client.upload_artifact(
            "1", "runs/1/asr/a1/x", b"x", content_type="application/json", content_sha256="0" * 64
        )

    if recovers:
        await send()
    else:
        with pytest.raises(ArtifactHashMismatchError) as failure:
            await send()
        assert failure.value.retryable is False
        assert failure.value.error_code == "ARTIFACT_UPLOAD_FAILED"
    assert len(fake_backend.calls("artifact_put")) == 2


def test_poll_wait_limit_is_checked_in_request_and_settings() -> None:
    from pydantic import ValidationError

    from npick_worker.settings import Settings

    with pytest.raises(ValidationError):
        ClaimRequest.model_validate({**_claim_request().model_dump(), "wait_seconds": 26})
    with pytest.raises(ValidationError):
        Settings(job_poll_wait_seconds=26)


@pytest.mark.asyncio
async def test_claim_uses_the_contract_path(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    await job_client.claim(_claim_request())
    assert fake_backend.requests[0].url.path == CLAIM_PATH


@pytest.mark.asyncio
async def test_empty_long_poll_is_not_a_failure(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    fake_backend.enqueue_empty_claim()
    response = await job_client.claim(_claim_request())
    assert response.assigned is False
    assert response.job is None


@pytest.mark.asyncio
async def test_empty_long_poll_does_not_sleep(
    job_client: JobApiClient, fake_backend: FakeBackend, _no_real_sleep: list[float]
) -> None:
    # 대기는 이미 서버가 했다. 여기서 또 자면 유휴 주기가 두 배가 된다.
    fake_backend.enqueue_empty_claim()
    await job_client.claim(_claim_request())
    assert _no_real_sleep == []


@pytest.mark.asyncio
async def test_claim_parses_assignment(job_client: JobApiClient, fake_backend: FakeBackend) -> None:
    fake_backend.enqueue_claim()
    response = await job_client.claim(_claim_request())
    assert response.assigned is True
    assert response.job is not None
    assert response.job.stage == "scene_detection"
    assert response.lease is not None
    assert response.lease.heartbeat_interval_ms == 10_000


@pytest.mark.asyncio
async def test_ids_stay_strings(job_client: JobApiClient, fake_backend: FakeBackend) -> None:
    # TSID bigint 는 JS 안전 정수를 넘는다. 어느 단계에서도 int 로 바뀌면 안 된다.
    fake_backend.enqueue_claim()
    response = await job_client.claim(_claim_request())
    assert response.job is not None
    assert response.job.pipeline_run_id == "398021847361024"


@pytest.mark.asyncio
async def test_claim_read_timeout_covers_the_server_wait(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    """서버가 25초를 붙잡는데 클라이언트가 2초에 끊으면 배정을 영영 못 받는다."""
    await job_client.claim(_claim_request())
    timeout: dict[str, Any] = fake_backend.requests[0].extensions["timeout"]
    assert timeout["read"] >= POLL_WAIT_SECONDS


@pytest.mark.asyncio
async def test_other_calls_keep_the_short_read_timeout(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    await job_client.heartbeat(
        "398021847361024", "scene_detection", HeartbeatRequest(lease_id="x", elapsed_ms=0)
    )
    timeout: dict[str, Any] = fake_backend.requests[0].extensions["timeout"]
    assert timeout["read"] < POLL_WAIT_SECONDS


# ── 인증 헤더 ────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_sends_bearer_token_and_worker_id(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    await job_client.claim(_claim_request())
    headers = fake_backend.requests[0].headers
    assert headers["authorization"] == "Bearer test-token"
    assert headers["x-worker-id"] == "test-worker"


@pytest.mark.asyncio
async def test_token_is_never_logged(
    job_client: JobApiClient,
    fake_backend: FakeBackend,
    caplog: pytest.LogCaptureFixture,
) -> None:
    fake_backend.enqueue_status("claim", 503)
    fake_backend.enqueue_claim()
    with caplog.at_level(logging.DEBUG):
        await job_client.claim(_claim_request())
    assert "test-token" not in caplog.text


# ── 봉투 ─────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_unwraps_the_api_response_envelope(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    fake_backend.enqueue(
        "claim",
        httpx2.Response(200, json=envelope({"assigned": True, "job": make_job()})),
    )
    response = await job_client.claim(_claim_request())
    assert response.job is not None


@pytest.mark.asyncio
async def test_is_success_false_preserves_the_backend_code(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    fake_backend.enqueue(
        "claim",
        httpx2.Response(200, json=envelope(None, is_success=False, code="JOB_503_001")),
    )
    with pytest.raises(JobApiUnavailableError, match="JOB_503_001"):
        await job_client.claim(_claim_request())


@pytest.mark.asyncio
async def test_unknown_response_fields_are_ignored(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    """BE 가 필드를 늘려도 워커가 죽으면 안 된다(전방 호환)."""
    fake_backend.enqueue(
        "claim",
        httpx2.Response(200, json=envelope({"assigned": False, "somethingNew": 1})),
    )
    assert (await job_client.claim(_claim_request())).assigned is False


# ── heartbeat / complete ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_heartbeat_uses_the_contract_path(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    await job_client.heartbeat(
        "398021847361024", "scene_detection", HeartbeatRequest(lease_id="x", elapsed_ms=1)
    )
    assert fake_backend.requests[0].url.path == HEARTBEAT_PATH


@pytest.mark.asyncio
async def test_heartbeat_409_is_lease_lost(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    fake_backend.enqueue_status("heartbeat", 409, code="JOB_409_002")
    with pytest.raises(LeaseLostError):
        await job_client.heartbeat(
            "398021847361024", "scene_detection", HeartbeatRequest(lease_id="x", elapsed_ms=1)
        )


@pytest.mark.asyncio
async def test_complete_uses_the_contract_path_and_idempotency_header(
    job_client: JobApiClient, fake_backend: FakeBackend, stage_result: Any
) -> None:
    await job_client.complete("398021847361024", "scene_detection", stage_result)
    request = fake_backend.requests[0]
    assert request.url.path == COMPLETE_PATH
    # 키는 BE 가 발급하고 워커는 되돌려 줄 뿐이다.
    assert request.headers["idempotency-key"] == stage_result.idempotency_key


@pytest.mark.asyncio
async def test_complete_body_carries_the_version_fields(
    job_client: JobApiClient, fake_backend: FakeBackend, stage_result: Any
) -> None:
    await job_client.complete("398021847361024", "scene_detection", stage_result)
    body = json.loads(fake_backend.requests[0].content)
    assert body["versions"]["stageVersion"]
    assert body["versions"]["outputSchemaVersion"]
    # 키가 사라지면 "모델이 없다" 와 "보고를 빠뜨렸다" 를 구분할 수 없다.
    assert "modelVersion" in body["versions"]
    assert "promptVersion" in body["versions"]
    assert body["envelopeVersion"] == "stage-result/v1"


@pytest.mark.asyncio
async def test_complete_409_is_lease_lost(
    job_client: JobApiClient, fake_backend: FakeBackend, stage_result: Any
) -> None:
    fake_backend.enqueue_status("complete", 409, code="JOB_409_002")
    with pytest.raises(LeaseLostError):
        await job_client.complete("398021847361024", "scene_detection", stage_result)


# ── 재시도 ───────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_5xx_is_retried(
    job_client: JobApiClient, fake_backend: FakeBackend, _no_real_sleep: list[float]
) -> None:
    fake_backend.enqueue_status("claim", 503)
    fake_backend.enqueue_status("claim", 502)
    fake_backend.enqueue_claim()
    response = await job_client.claim(_claim_request())
    assert response.assigned is True
    assert len(fake_backend.calls("claim")) == 3
    assert len(_no_real_sleep) == 2


@pytest.mark.asyncio
async def test_backoff_grows(
    job_client: JobApiClient, fake_backend: FakeBackend, _no_real_sleep: list[float]
) -> None:
    for _ in range(5):
        fake_backend.enqueue_status("claim", 503)
    with pytest.raises(JobApiUnavailableError):
        await job_client.claim(_claim_request())
    # full jitter 라 값은 흔들리지만 상한은 자란다. 상한을 넘지 않는 것만 확인한다.
    assert all(delay <= 1.0 for delay in _no_real_sleep)


@pytest.mark.asyncio
async def test_retry_after_is_honored(
    job_client: JobApiClient, fake_backend: FakeBackend, _no_real_sleep: list[float]
) -> None:
    fake_backend.enqueue_status("claim", 429, headers={"Retry-After": "0.5"})
    fake_backend.enqueue_claim()
    await job_client.claim(_claim_request())
    assert _no_real_sleep == [0.5]


@pytest.mark.asyncio
async def test_401_is_permanent_and_not_retried(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    fake_backend.enqueue_status("claim", 401, code="JOB_401")
    with pytest.raises(JobApiUnauthorizedError):
        await job_client.claim(_claim_request())
    assert len(fake_backend.calls("claim")) == 1


@pytest.mark.asyncio
async def test_transport_error_is_retried_then_reported() -> None:
    attempts = 0

    def explode(request: httpx2.Request) -> httpx2.Response:
        nonlocal attempts
        attempts += 1
        msg = "연결 실패"
        raise httpx2.ConnectError(msg)

    client = JobApiClient(
        base_url="https://backend.test",
        token="t",
        worker_id="w",
        connect_timeout=1.0,
        read_timeout=1.0,
        poll_wait_seconds=1,
        max_backoff_seconds=0.01,
        max_attempts=3,
        transport=httpx2.MockTransport(explode),
    )
    with pytest.raises(JobApiUnavailableError):
        await client.claim(_claim_request())
    assert attempts == 3
    await client.aclose()


# ── artifacts ────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_download_input_writes_the_file(
    job_client: JobApiClient, fake_backend: FakeBackend, tmp_path: Path
) -> None:
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=b"video-bytes"))
    dest = tmp_path / "source.mp4"
    await job_client.download_input("398021847361024", "clips/a/source.mp4", dest)
    assert dest.read_bytes() == b"video-bytes"


@pytest.mark.asyncio
async def test_upload_artifact_sends_the_hash_header(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    await job_client.upload_artifact(
        "398021847361024",
        "runs/398021847361024/scene_detection/a1/frame.png",
        b"png",
        content_type="image/png",
        content_sha256="a71c",
    )
    request = fake_backend.calls("artifact_put")[0]
    assert request.headers["x-content-sha256"] == "a71c"
    assert request.content == b"png"


# ── 409·403·404 하위 코드 (계약 §9.1) ────────────────────────────────
# 상태 코드만 보면 "중단해야 하는 충돌" 과 "폐기하면 되는 충돌" 이 한 덩어리가 된다.


@pytest.mark.asyncio
async def test_409_001_is_stage_already_completed(
    job_client: JobApiClient, fake_backend: FakeBackend, stage_result: Any
) -> None:
    """이미 성공한 단계다. lease 를 잃은 것이 아니다."""
    fake_backend.enqueue_status("complete", 409, code="JOB_409_001")
    with pytest.raises(StageAlreadyCompletedError):
        await job_client.complete("398021847361024", "scene_detection", stage_result)


@pytest.mark.asyncio
async def test_409_003_is_conflict_not_lease_lost(
    job_client: JobApiClient, fake_backend: FakeBackend, stage_result: Any
) -> None:
    """같은 키에 다른 본문. 워커 버그이지 lease 문제가 아니다."""
    fake_backend.enqueue_status("complete", 409, code="JOB_409_003")
    with pytest.raises(JobApiConflictError):
        await job_client.complete("398021847361024", "scene_detection", stage_result)


@pytest.mark.asyncio
async def test_409_004_is_conflict_not_lease_lost(
    job_client: JobApiClient, fake_backend: FakeBackend, stage_result: Any
) -> None:
    """stageVersion 불일치. 워커를 다시 배포해야 한다."""
    fake_backend.enqueue_status("complete", 409, code="JOB_409_004")
    with pytest.raises(JobApiConflictError):
        await job_client.complete("398021847361024", "scene_detection", stage_result)


@pytest.mark.asyncio
async def test_unknown_409_is_conflict_not_lease_lost(
    job_client: JobApiClient, fake_backend: FakeBackend, stage_result: Any
) -> None:
    """모르는 충돌은 폐기한다. 이해 못 하는 상황에서 쓰기를 밀어붙이지 않는다."""
    fake_backend.enqueue_status("complete", 409, code="JOB_409_999")
    with pytest.raises(JobApiConflictError):
        await job_client.complete("398021847361024", "scene_detection", stage_result)


@pytest.mark.asyncio
async def test_403_001_does_not_stop_the_worker(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    """산출물 키가 접두 밖인 것은 단계 문제다. 워커 전체를 멈출 이유가 없다."""
    fake_backend.enqueue_status("artifact_put", 403, code="JOB_403_001")
    with pytest.raises(ArtifactKeyRejectedError):
        await job_client.upload_artifact(
            "398021847361024",
            "elsewhere/x.png",
            b"png",
            content_type="image/png",
            content_sha256="a71c",
        )


@pytest.mark.asyncio
async def test_403_002_is_unauthorized(job_client: JobApiClient, fake_backend: FakeBackend) -> None:
    """fleet 불일치. 이 워커는 이 서버에서 일하면 안 된다."""
    fake_backend.enqueue_status("claim", 403, code="JOB_403_002")
    with pytest.raises(JobApiUnauthorizedError):
        await job_client.claim(_claim_request())


@pytest.mark.asyncio
async def test_download_404_is_input_unavailable(
    job_client: JobApiClient, fake_backend: FakeBackend, tmp_path: Path
) -> None:
    """없는 입력을 다시 받아도 없다."""
    fake_backend.enqueue_status("artifact_get", 404, code="JOB_404_002", streamed=True)
    with pytest.raises(InputUnavailableError) as caught:
        await job_client.download_input("398021847361024", "clips/a/gone.mp4", tmp_path / "x.mp4")
    assert caught.value.retryable is False


# ── worker_id ────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_worker_id_header_matches_the_client_property(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    """헤더와 본문이 서로 다른 ID 를 말하면 BE 로그에서 워커를 추적할 수 없다."""
    await job_client.claim(_claim_request())
    assert fake_backend.requests[0].headers["x-worker-id"] == job_client.worker_id
    assert job_client.worker_id != ""


# ── 다운로드 오류 경로 ───────────────────────────────────────────────
# 스트리밍으로 받는 경로라 응답이 아직 읽히지 않은 상태다. 판정 전에 읽지 않으면
# ResponseNotRead 가 나고, 그것은 StreamError(RuntimeError) 라 _error_code 의
# except ValueError 에 걸리지 않는다. streamed=True 가 그 상태를 만든다.


@pytest.mark.asyncio
async def test_download_500_is_reported_as_media_unavailable(
    job_client: JobApiClient, fake_backend: FakeBackend, tmp_path: Path
) -> None:
    """미디어를 못 가져온 것이 "이 단계가 실패했다" 로 둔갑하면 안 된다."""
    fake_backend.enqueue_status("artifact_get", 500, streamed=True)
    with pytest.raises(InputDownloadError) as caught:
        await job_client.download_input("398021847361024", "clips/a/x.mp4", tmp_path / "x.mp4")
    assert caught.value.error_code == "MEDIA_UNAVAILABLE"


@pytest.mark.asyncio
async def test_download_500_is_transient(
    job_client: JobApiClient, fake_backend: FakeBackend, tmp_path: Path
) -> None:
    """서버 사정이다. 같은 입력을 다시 받으면 될 수 있다."""
    fake_backend.enqueue_status("artifact_get", 500, streamed=True)
    with pytest.raises(InputDownloadError) as caught:
        await job_client.download_input("398021847361024", "clips/a/x.mp4", tmp_path / "x.mp4")
    assert caught.value.retryable is True


@pytest.mark.asyncio
async def test_download_403_002_stays_unauthorized(
    job_client: JobApiClient, fake_backend: FakeBackend, tmp_path: Path
) -> None:
    """토큰 문제는 단계 문제가 아니다. 미디어 오류로 감싸면 워커가 계속 돈다."""
    fake_backend.enqueue_status("artifact_get", 403, code="JOB_403_002", streamed=True)
    with pytest.raises(JobApiUnauthorizedError):
        await job_client.download_input("398021847361024", "clips/a/x.mp4", tmp_path / "x.mp4")


# ── 재시도 소진 ──────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_last_attempt_does_not_sleep(
    job_client: JobApiClient, fake_backend: FakeBackend, _no_real_sleep: list[float]
) -> None:
    """실패가 확정된 뒤에 자면 그만큼 늦게 보고할 뿐이다."""
    for _ in range(5):
        fake_backend.enqueue_status("claim", 503)
    with pytest.raises(JobApiUnavailableError):
        await job_client.claim(_claim_request())
    assert len(fake_backend.calls("claim")) == 5
    assert len(_no_real_sleep) == 4


# ── 산출물 키 ────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_artifact_key_does_not_leak_into_the_query(
    job_client: JobApiClient, fake_backend: FakeBackend
) -> None:
    """키에 ? 나 # 가 있으면 인코딩하지 않은 경로가 잘린다.

    공백·비ASCII 는 httpx2 가 이미 퍼센트 인코딩한다. 여기서 잡는 것은 구분자다.
    """
    await job_client.upload_artifact(
        "398021847361024",
        "runs/398021847361024/scene_detection/a1/x.png?evil=1#frag",
        b"png",
        content_type="image/png",
        content_sha256="a71c",
    )
    url = fake_backend.calls("artifact_put")[0].url
    assert url.query == b""
    assert url.path.endswith("/a1/x.png?evil=1#frag")


@pytest.mark.asyncio
async def test_artifact_key_escaping_the_path_is_rejected(job_client: JobApiClient) -> None:
    """.. 는 인코딩으로 막히지 않는다 — 구분자를 남기므로 경로가 정규화된다.

    그대로 두면 요청이 아예 다른 엔드포인트로 가고, 접두 검사(JOB_403_001)가 돌지 않는다.
    """
    with pytest.raises(ArtifactKeyRejectedError):
        await job_client.upload_artifact(
            "398021847361024",
            "runs/398021847361024/../../../admin",
            b"png",
            content_type="image/png",
            content_sha256="a71c",
        )


@pytest.mark.asyncio
async def test_transport_error_during_body_is_media_unavailable(
    job_client: JobApiClient, fake_backend: FakeBackend, tmp_path: Path
) -> None:
    """본문 순회 중 끊긴 연결이 "장면 분할 실패" 로 둔갑하면 안 된다.

    헤더 교환은 성공하므로 send() 를 감싸는 핸들러로는 잡히지 않는다. 날 ReadError 가
    올라가면 classify() 가 OSError 로도 보지 못해 단계별 기본값으로 떨어진다.
    """
    fake_backend.enqueue("artifact_get", httpx2.Response(200, stream=BreakingStream()))
    with pytest.raises(InputDownloadError) as caught:
        await job_client.download_input("398021847361024", "clips/a/x.mp4", tmp_path / "x.mp4")
    assert caught.value.error_code == "MEDIA_UNAVAILABLE"
    assert caught.value.retryable is True


@pytest.mark.asyncio
async def test_backslash_traversal_is_rejected(job_client: JobApiClient) -> None:
    """판정은 POSIX·Windows 양쪽 규칙으로 한다 — docstring 이 그렇게 말한다.

    quote 가 중화하고 BE 의 접두 검사에도 걸리므로 노출은 없었다. 주석이 코드보다
    세게 말하는 것이 문제였다.
    """
    with pytest.raises(ArtifactKeyRejectedError):
        await job_client.upload_artifact(
            "398021847361024",
            r"runs\398021847361024\..\..\admin.png",
            b"png",
            content_type="image/png",
            content_sha256="a71c",
        )
