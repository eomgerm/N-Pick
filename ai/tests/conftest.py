import json
from collections import defaultdict, deque
from collections.abc import AsyncIterator, Callable, Iterator, Sequence
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Literal

import av
import httpx2
import numpy as np
import pytest
from fastapi.testclient import TestClient

from npick_worker.app import create_app
from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.models import StageResult
from npick_worker.jobs.versions import StageVersion
from npick_worker.settings import get_settings


@pytest.fixture(autouse=True)
def reset_settings() -> Iterator[None]:
    """Settings 는 프로세스 수명 동안 캐시된다. 환경 변수를 건드리는 테스트가
    다음 테스트로 새지 않게 매번 비운다."""
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(create_app()) as test_client:
        yield test_client


# ── 합성 영상 픽스처 ────────────────────────────────────────────────────
# 영상 파일을 저장소에 커밋하지 않는다. tests/test_smoke_models.py 가 무음
# 오디오를 in-process 로 만드는 것과 같은 이유다 — 바이너리 픽스처는 리뷰할 수
# 없고, 인코더가 바뀌면 조용히 다른 걸 테스트하게 된다.

VIDEO_WIDTH = 160
VIDEO_HEIGHT = 120
VIDEO_FPS = 10

#: 패널 종류. 색만 다른 프레임은 content_val 이 20 근처라 기본 임계값 27 을
#: 넘지 못한다(실측). 구조까지 다른 패널을 써야 실제 hard cut 과 비슷해진다.
PanelKind = str


def _panel(kind: PanelKind) -> np.ndarray:
    frame = np.zeros((VIDEO_HEIGHT, VIDEO_WIDTH, 3), dtype=np.uint8)
    if kind == "bars":
        frame[:, ::8] = 255
    elif kind == "white":
        frame[:, :] = 235
    elif kind == "noise":
        # 시드 고정 — 픽스처가 실행마다 달라지면 멱등성 테스트가 무의미해진다.
        rng = np.random.default_rng(7)
        frame[:, :] = rng.integers(0, 256, frame.shape, dtype=np.uint8)
    elif kind == "gray":
        frame[:, :] = 128
    else:  # pragma: no cover - 오타 방지용
        msg = f"알 수 없는 패널: {kind}"
        raise ValueError(msg)
    return frame


def _write_video(path: Path, blocks: Sequence[tuple[PanelKind, int]]) -> Path:
    with av.open(str(path), "w") as container:
        stream = container.add_stream("libx264", rate=VIDEO_FPS)
        stream.width = VIDEO_WIDTH
        stream.height = VIDEO_HEIGHT
        stream.pix_fmt = "yuv420p"
        # ultrafast + 낮은 crf: 테스트가 1초 안에 끝나야 하고, 압축 아티팩트가
        # 경계 판정을 흔들면 안 된다.
        stream.options = {"crf": "16", "preset": "ultrafast"}
        for kind, frame_count in blocks:
            array = _panel(kind)
            for _ in range(frame_count):
                for packet in stream.encode(av.VideoFrame.from_ndarray(array, format="rgb24")):
                    container.mux(packet)
        for packet in stream.encode():
            container.mux(packet)
    return path


@pytest.fixture
def make_video(tmp_path: Path) -> Callable[[str, Sequence[tuple[PanelKind, int]]], Path]:
    """`make_video("name", [("bars", 20), ("white", 20)])` → mp4 경로.

    블록 하나가 정지 화면 한 덩어리다. 10fps 이므로 20프레임 = 2000ms.
    """

    def factory(name: str, blocks: Sequence[tuple[PanelKind, int]]) -> Path:
        return _write_video(tmp_path / f"{name}.mp4", blocks)

    return factory


# ── 가짜 서비스 서버 ────────────────────────────────────────────────────
# 왕복을 확인하려면 상대가 있어야 하는데 BE 잡 API 는 아직 구현되지 않았다.
# 계약(docs/contracts/job-api.md)이 정한 모양대로만 응답하는 최소 서버를 여기 둔다.
# respx 는 쓸 수 없다 — httpx 0.x 에 핀이 걸려 있고 이 환경에는 httpx2 만 있다.

RouteName = Literal["claim", "heartbeat", "complete", "artifact_get", "artifact_put"]


def envelope(
    data: object,
    *,
    is_success: bool = True,
    code: str = "COMM_200",
    message: str = "OK",
) -> dict[str, object]:
    """BE 의 ApiResponse 봉투. 필드 순서와 이름은 ApiResponse.java 를 따른다."""
    return {
        "isSuccess": is_success,
        "code": code,
        "message": message,
        "timestamp": "2026-09-07T09:20:19Z",
        "path": "POST /api/v1/internal/jobs/claim",
        "data": data,
    }


def make_lease(**overrides: object) -> dict[str, object]:
    lease: dict[str, object] = {
        "leaseId": "7d1f4e0c-3a55-4a10-8f60-2e1b9c0d4a77",
        "leaseUntil": "2026-09-07T09:21:19Z",
        "heartbeatIntervalMs": 10_000,
    }
    lease.update(overrides)
    return lease


def make_job(**overrides: object) -> dict[str, object]:
    job: dict[str, object] = {
        "pipelineRunId": "398021847361024",
        "clipId": "398021840012345",
        "processingNo": 1,
        "stage": "scene_detection",
        "attempt": 1,
        "maxAttempts": 1,
        "idempotencyKey": "398021847361024:scene_detection:1",
        "pipelineVersion": "npick-pipeline/v1:64960bae4565",
        "outputKeyPrefix": "runs/398021847361024/scene_detection/a1/",
        "inputs": {
            "media": {
                "storageKey": "clips/398021840012345/source.mp4",
                "transport": "shared-volume",
                "localPath": "clips/398021840012345/source.mp4",
            }
        },
    }
    job.update(overrides)
    return job


class UnreadStream(httpx2.AsyncByteStream):
    """아직 읽지 않은 응답 본문. 실서버의 스트리밍 응답과 같은 상태를 만든다.

    `httpx2.Response(json=...)` 는 생성 시점에 `read()` 되어 `_content` 가 채워진다
    (`httpx2/_models.py` Response.__init__). 그 응답으로는 "본문을 읽지 않고 판정했다"
    는 결함이 재현되지 않는다 — `response.json()` 이 그냥 성공해 버린다.
    """

    def __init__(self, body: bytes) -> None:
        self._body = body

    async def __aiter__(self) -> AsyncIterator[bytes]:
        yield self._body


class FakeBackend:
    """(method, path) 로 라우팅하고 모든 요청을 기록한다.

    큐가 비면 각 경로의 무해한 기본값을 돌려준다 — 테스트가 자기 관심사만 적도록.
    """

    def __init__(self) -> None:
        self.requests: list[httpx2.Request] = []
        self._queues: dict[RouteName, deque[httpx2.Response]] = defaultdict(deque)
        #: 응답을 돌려준 직후 부르는 훅. "실행 중에 heartbeat 가 왔다" 같은 순서를
        #: 타이밍에 기대지 않고 재현할 때 쓴다.
        self.on_request: Callable[[RouteName, httpx2.Request], None] | None = None

    # 준비 --------------------------------------------------------------

    def enqueue(self, route: RouteName, response: httpx2.Response) -> None:
        self._queues[route].append(response)

    def enqueue_claim(
        self,
        job: dict[str, object] | None = None,
        lease: dict[str, object] | None = None,
    ) -> None:
        payload = {
            "assigned": True,
            "lease": lease if lease is not None else make_lease(),
            "job": job if job is not None else make_job(),
        }
        self.enqueue("claim", httpx2.Response(200, json=envelope(payload)))

    def enqueue_empty_claim(self) -> None:
        self.enqueue("claim", httpx2.Response(200, json=envelope({"assigned": False})))

    def enqueue_heartbeat_abort(self, reason: str = "RUN_CANCELLED") -> None:
        self.enqueue(
            "heartbeat",
            httpx2.Response(200, json=envelope({"command": "abort", "abortReason": reason})),
        )

    def enqueue_complete_with_next(
        self,
        job: dict[str, object],
        lease: dict[str, object] | None = None,
    ) -> None:
        """complete 응답에 다음 단계 선배정을 실어 준다."""
        payload = {
            "accepted": True,
            "duplicate": False,
            "next": {
                "assigned": True,
                "lease": lease if lease is not None else make_lease(),
                "job": job,
            },
        }
        self.enqueue("complete", httpx2.Response(200, json=envelope(payload)))

    def enqueue_status(
        self,
        route: RouteName,
        status: int,
        *,
        code: str = "JOB_500",
        headers: dict[str, str] | None = None,
        streamed: bool = False,
    ) -> None:
        """오류 응답 하나를 큐에 넣는다.

        `streamed` 는 `stream=True` 로 받는 경로(`download_input`)를 위한 것이다.
        기본값이 만드는 응답은 이미 읽힌 상태라, 본문을 읽지 않고 판정하는 결함이
        그 위에서는 드러나지 않는다. `UnreadStream` 을 참고.
        """
        body = envelope(None, is_success=False, code=code, message="fake")
        if streamed:
            merged = {"content-type": "application/json"} | (headers or {})
            self.enqueue(
                route,
                httpx2.Response(
                    status,
                    headers=merged,
                    stream=UnreadStream(json.dumps(body).encode()),
                ),
            )
            return
        self.enqueue(route, httpx2.Response(status, json=body, headers=headers))

    # 검사 --------------------------------------------------------------

    def calls(self, route: RouteName) -> list[httpx2.Request]:
        return [r for r in self.requests if self._route(r) == route]

    def body(self, route: RouteName, index: int = 0) -> dict[str, Any]:
        payload: dict[str, Any] = json.loads(self.calls(route)[index].content)
        return payload

    # 처리 --------------------------------------------------------------

    def __call__(self, request: httpx2.Request) -> httpx2.Response:
        self.requests.append(request)
        route = self._route(request)
        queued = self._queues[route]
        response = queued.popleft() if queued else self._default(route)
        if self.on_request is not None:
            self.on_request(route, request)
        return response

    @staticmethod
    def _route(request: httpx2.Request) -> RouteName:
        path = request.url.path
        if path.endswith("/claim"):
            return "claim"
        if path.endswith("/heartbeat"):
            return "heartbeat"
        if path.endswith("/complete"):
            return "complete"
        return "artifact_get" if request.method == "GET" else "artifact_put"

    @staticmethod
    def _default(route: RouteName) -> httpx2.Response:
        if route == "claim":
            return httpx2.Response(200, json=envelope({"assigned": False}))
        if route == "heartbeat":
            return httpx2.Response(
                200,
                json=envelope({"command": "continue", "leaseUntil": "2026-09-07T09:22:19Z"}),
            )
        if route == "complete":
            return httpx2.Response(200, json=envelope({"accepted": True, "duplicate": False}))
        if route == "artifact_get":
            return httpx2.Response(404, json=envelope(None, is_success=False, code="JOB_404_002"))
        return httpx2.Response(201)


@pytest.fixture
def fake_backend() -> FakeBackend:
    return FakeBackend()


@pytest.fixture
def job_client(fake_backend: FakeBackend) -> Iterator[JobApiClient]:
    client = JobApiClient(
        base_url="https://backend.test",
        token="test-token",
        worker_id="test-worker",
        connect_timeout=1.0,
        read_timeout=2.0,
        poll_wait_seconds=25,
        max_backoff_seconds=1.0,
        transport=httpx2.MockTransport(fake_backend),
    )
    yield client


@pytest.fixture
def stage_result() -> StageResult:
    """성공한 scene_detection 결과 하나. 봉투 모양을 확인하는 테스트가 쓴다."""
    started = datetime(2026, 9, 7, 9, 20, 19, tzinfo=UTC)
    return StageResult(
        lease_id="7d1f4e0c-3a55-4a10-8f60-2e1b9c0d4a77",
        idempotency_key="398021847361024:scene_detection:1",
        stage="scene_detection",
        attempt=1,
        status="succeeded",
        started_at=started,
        finished_at=started + timedelta(milliseconds=41_230),
        duration_ms=41_230,
        versions=StageVersion(
            stage_version="npick.stage.scene_detection/v1:0badc0de",
            output_schema_version="npick.stage.scene_detection.output/v1",
            config_version="scene-detect/v1:20dfc0a6",
            detail={"engine": "pyscenedetect", "engineVersion": "0.7.1", "detector": "content"},
        ),
        output={"scenes": [], "mediaDurationMs": 76067, "frameRate": 30.0},
    )
