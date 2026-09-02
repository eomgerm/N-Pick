from collections.abc import Callable, Iterator, Sequence
from pathlib import Path

import av
import numpy as np
import pytest
from fastapi.testclient import TestClient

from npick_worker.app import create_app


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
    container = av.open(str(path), "w")
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
    container.close()
    return path


@pytest.fixture
def make_video(tmp_path: Path) -> Callable[[str, Sequence[tuple[PanelKind, int]]], Path]:
    """`make_video("name", [("bars", 20), ("white", 20)])` → mp4 경로.

    블록 하나가 정지 화면 한 덩어리다. 10fps 이므로 20프레임 = 2000ms.
    """

    def factory(name: str, blocks: Sequence[tuple[PanelKind, int]]) -> Path:
        return _write_video(tmp_path / f"{name}.mp4", blocks)

    return factory
