"""가중치 로딩 중의 동작 (S15P21A501-164 리뷰 지적).

**로딩은 요청 경로를 막으면 안 된다.** 워밍업이 상한을 넘겨도 스레드는 계속 돌고,
그 사이에 들어온 검색은 dense 채널 없이 BM25 로 즉시 나가야 한다(FRD v3.1 §6.2).

그 동작을 만드는 코드가 없었다 — `_ensure_loaded()` 가 락도 준비 상태 확인도 없이
`if self._model is None` 만 보므로, 로딩 중 요청이 **같은 인스턴스에서 모델을 또**
올리며 블록했다. 동시 요청 수만큼 1.7GB 로딩이 겹칠 수 있었다.

여기서 지키는 것 셋.

1. 준비 전 요청은 **블록하지 않고** 즉시 임베딩 없이 나간다
2. 동시에 여러 스레드가 들어와도 `_load` 는 **한 번만** 돈다
3. 로딩이 끝나면 그 다음 요청부터 벡터가 나온다
"""

import threading
import time
from collections.abc import Sequence
from typing import Any

import pytest
from fastapi.testclient import TestClient

from npick_worker import query_api
from npick_worker.text_embedding.sentence_transformers_backend import (
    SentenceTransformerEncoder,
)
from tests.conftest import SCENE_EMBEDDING_DIMENSION

RAW_QUERY = "작년 여름에 부산 침수됐던 장면 좀 찾아줘"

#: 느린 로딩을 흉내내는 시간. 준비 전 요청이 이 시간에 끌려가지 않는 것을 본다.
SLOW_LOAD_SECONDS = 10.0


class _SlowLoadingEncoder:
    """가중치 로딩이 느린 인코더. `SentenceTransformerEncoder` 와 같은 표면을 쓴다."""

    name = "slow"
    version = "slow@v0"
    model_version = "slow/model@0"

    def __init__(self) -> None:
        self._model: object | None = None
        self._lock = threading.Lock()
        self.load_calls = 0
        self.release = threading.Event()

    @property
    def is_ready(self) -> bool:
        return self._model is not None

    def _ensure_loaded(self) -> object:
        if self._model is not None:
            return self._model
        with self._lock:
            if self._model is None:
                self.load_calls += 1
                self.release.wait(timeout=SLOW_LOAD_SECONDS)
                self._model = object()
        return self._model

    def encode(self, texts: Sequence[str]) -> tuple[tuple[float, ...], ...]:
        self._ensure_loaded()
        return tuple(
            tuple(float(i % 7 + 1) for i in range(SCENE_EMBEDDING_DIMENSION)) for _ in texts
        )


@pytest.fixture
def slow_encoder(monkeypatch: pytest.MonkeyPatch) -> _SlowLoadingEncoder:
    fake = _SlowLoadingEncoder()
    monkeypatch.setattr(query_api, "_encoder", lambda: fake)
    return fake


def test_request_during_loading_does_not_block(
    client: TestClient, slow_encoder: _SlowLoadingEncoder
) -> None:
    """**준비 전 요청은 로딩을 기다리지 않는다.**

    기다리면 검색이 동기 예산(p95 10초)을 통째로 날린다. FRD v3.1 §6.2 가 정한 것은
    "의미 검색 실패 → 단어 검색으로 결과 제공" 이지 "의미 검색이 준비될 때까지 대기"
    가 아니다.
    """
    begin = time.perf_counter()
    response = client.post("/query/resolve", json={"query": RAW_QUERY})
    elapsed = time.perf_counter() - begin

    assert response.status_code == 200
    body = response.json()
    assert body["embedding"] is None
    assert body["embedding_error"]["category"] == "EMBEDDING_FAILED"
    # BM25 는 이어져야 한다 — 이 응답의 토큰으로 단어 검색이 돈다.
    assert body["normalization"]["search_tokens"]
    assert elapsed < SLOW_LOAD_SECONDS / 2, (
        f"준비 전 요청이 로딩을 기다렸다 ({elapsed:.1f}초). 동기 예산 안에서 BM25 로 빠져야 한다"
    )


def test_request_during_loading_does_not_start_another_load(
    client: TestClient, slow_encoder: _SlowLoadingEncoder
) -> None:
    """요청 경로가 가중치 로딩에 **진입하지 않는다.**

    진입하면 동시 요청 수만큼 1.7GB 로딩이 겹친다.
    """
    client.post("/query/resolve", json={"query": RAW_QUERY})
    client.post("/query/resolve", json={"query": RAW_QUERY})

    assert slow_encoder.load_calls == 0, "요청이 모델 로딩을 시작했다"


def test_vectors_flow_once_loading_finishes(
    client: TestClient, slow_encoder: _SlowLoadingEncoder
) -> None:
    """워밍업이 늦게라도 끝나면 그 다음 요청부터는 정상이다."""
    slow_encoder.release.set()
    slow_encoder._ensure_loaded()  # 워밍업 스레드가 뒤늦게 끝난 상황

    body = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    assert len(body["embedding"]) == SCENE_EMBEDDING_DIMENSION
    assert body["embedding_error"] is None


# ── 어댑터 자체의 single-flight ────────────────────────────────────────


def test_concurrent_ensure_loaded_loads_once(monkeypatch: pytest.MonkeyPatch) -> None:
    """**락이 없으면 스레드마다 가중치를 올린다.**

    `shared_encoder()` 의 `lru_cache` 는 같은 인스턴스를 돌려줄 뿐 이걸 막지 못한다 —
    같은 객체에서 여러 스레드가 `self._model is None` 을 동시에 참으로 본다.

    워밍업 스레드와 (배선된) 워커 잡이 겹치는 경로라 어댑터 쪽에도 방어가 필요하다.
    """
    calls: list[int] = []
    barrier = threading.Barrier(4)

    def slow_load(*args: Any, **kwargs: Any) -> tuple[Any, str, int]:
        calls.append(1)
        time.sleep(0.2)
        return object(), "a" * 40, SCENE_EMBEDDING_DIMENSION

    monkeypatch.setattr(
        "npick_worker.text_embedding.sentence_transformers_backend._load", slow_load
    )
    encoder = SentenceTransformerEncoder("some/model", batch_size=1)

    def worker() -> None:
        barrier.wait()
        encoder._ensure_loaded()

    threads = [threading.Thread(target=worker) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=10)

    assert len(calls) == 1, f"가중치를 {len(calls)} 번 올렸다. single-flight 가 없다"


def test_is_ready_does_not_trigger_a_load(monkeypatch: pytest.MonkeyPatch) -> None:
    """상태 조회가 부작용을 갖지 않는다. 갖는 순간 gate 가 gate 를 무너뜨린다."""
    monkeypatch.setattr(
        "npick_worker.text_embedding.sentence_transformers_backend._load",
        lambda *a, **k: pytest.fail("is_ready 가 가중치를 올렸다"),
    )
    encoder = SentenceTransformerEncoder("some/model", batch_size=1)

    assert encoder.is_ready is False
