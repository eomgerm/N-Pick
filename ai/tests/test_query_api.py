"""`POST /query/resolve` 계약 검증 (S15P21A501-45).

핵심은 셋이다.

1. 해석에 넘어가는 것이 **원문**인가 — 정규화 질의를 넘기면 span 이 어긋나 explicit
   anchor 가 전부 강등된다.
2. 해석이 실패해도 정규화가 살아 나오는가 — FRD v3.1 §6.2 의 BM25 fallback 에
   `search_tokens` 가 필요하다.
3. 정규화 자체가 불가능한 질의만 400 인가.

실제 LLM 은 부르지 않는다. `QueryResolver` Protocol 을 스텁으로 갈아끼운다.
"""

import json
from collections.abc import Callable, Sequence

import pytest
from fastapi.testclient import TestClient

from npick_worker import query_api
from npick_worker.query_resolver import ResolverCallError, ResolverSchemaInvalidError
from npick_worker.query_resolver.gms_backend import _NETWORK, _RATE_LIMITED, _TIMEOUT
from npick_worker.query_resolver.ollama_backend import _NETWORK as _OLLAMA_NETWORK
from npick_worker.settings import get_settings
from npick_worker.text_embedding.encoder import (
    EmbeddingCallError,
    EmbeddingModelUnavailableError,
)
from tests.conftest import SCENE_EMBEDDING_DIMENSION

RAW_QUERY = "작년 여름에 부산 침수됐던 장면 좀 찾아줘"


class StubResolver:
    """`QueryResolver` Protocol 스텁. 받은 user prompt 를 기록한다."""

    def __init__(self, payload: str | Exception) -> None:
        self._payload = payload
        self.user_prompts: list[str] = []

    @property
    def name(self) -> str:
        return "stub"

    @property
    def version(self) -> str:
        return "stub@v0"

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        self.user_prompts.append(user_prompt)
        if isinstance(self._payload, Exception):
            raise self._payload
        return self._payload


def _valid_payload() -> str:
    """원문에 실제로 있는 값만 explicit_query 로 주장한다."""
    return json.dumps(
        {
            "intent": "scene_search",
            "date_windows": [],
            "incident_names": [],
            "entities": [],
            "locations": [
                {
                    "type": "location",
                    "value": "부산",
                    "origin": "explicit_query",
                    "query_span": {"start": 7, "end": 9},
                    "confidence": 0.9,
                }
            ],
            "classifications": [],
            "expanded_terms": ["수해"],
            "confidence": 0.8,
        },
        ensure_ascii=False,
    )


StubFactory = Callable[[str | Exception], StubResolver]


@pytest.fixture
def stub(monkeypatch: pytest.MonkeyPatch) -> StubFactory:
    def install(payload: str | Exception) -> StubResolver:
        resolver = StubResolver(payload)
        monkeypatch.setattr(query_api, "_resolver", lambda: resolver)
        return resolver

    return install


class StubEncoder:
    """`TextEncoder` 스텁. 받은 텍스트를 기록하고 고정 벡터를 돌려준다.

    **차원을 상수로 박는다.** 설정에서 읽으면 "응답 길이가 설정과 같다" 는 단정이
    순환이 된다 — 스텁과 단정이 같은 출처를 보므로 어떤 값이든 통과한다. 여기 1024 는
    `scene.embedding vector(1024)` 컬럼의 값이고 그것이 정본이다.
    """

    name = "stub"
    version = "stub@v0"
    model_version = "stub/embedding@0"

    def __init__(self, failure: Exception | None = None) -> None:
        self._failure = failure
        self._dimension = SCENE_EMBEDDING_DIMENSION
        self.texts: list[str] = []

    def encode(self, texts: Sequence[str]) -> tuple[tuple[float, ...], ...]:
        self.texts.extend(texts)
        if self._failure is not None:
            raise self._failure
        # 성분을 전부 1 로 두지 않는다 — 정규화가 걸렸는지 norm 으로만 보면
        # 어떤 상수 벡터든 통과한다.
        return tuple(tuple(float(index % 7 + 1) for index in range(self._dimension)) for _ in texts)


@pytest.fixture(autouse=True)
def encoder(monkeypatch: pytest.MonkeyPatch) -> StubEncoder:
    """**autouse 다.** 배선 후에는 모든 요청이 인코더를 거치므로, 스텁을 깔지 않으면
    이 파일의 모든 테스트가 1.7GB 가중치를 내려받으려 든다."""
    fake = StubEncoder()
    monkeypatch.setattr(query_api, "_encoder", lambda: fake)
    return fake


@pytest.fixture
def broken_encoder(monkeypatch: pytest.MonkeyPatch) -> Callable[[Exception], StubEncoder]:
    def install(failure: Exception) -> StubEncoder:
        fake = StubEncoder(failure)
        monkeypatch.setattr(query_api, "_encoder", lambda: fake)
        return fake

    return install


def test_sends_raw_query_to_resolver(client: TestClient, stub: StubFactory) -> None:
    resolver = stub(_valid_payload())

    response = client.post("/query/resolve", json={"query": RAW_QUERY})

    assert response.status_code == 200
    # 정규화 질의("부산 여름 작년 침수" 류)가 아니라 원문이 그대로 들어가야 한다.
    assert RAW_QUERY in resolver.user_prompts[0]


def test_returns_normalization_and_resolution(client: TestClient, stub: StubFactory) -> None:
    stub(_valid_payload())

    body = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    normalization = body["normalization"]
    assert normalization["normalized_query"]
    assert normalization["search_tokens"]
    assert normalization["normalization_version"]

    assert body["error"] is None
    assert body["resolution"]["intent"] == "scene_search"
    assert body["resolution"]["schema_version"] == body["resolution_schema_version"]
    assert body["prompt_version"]
    assert body["model_version"] == "stub@v0"


@pytest.mark.parametrize(
    ("failure", "expected"),
    [
        (ResolverCallError("timed out", category=_TIMEOUT), _TIMEOUT),
        (ResolverCallError("429", category=_RATE_LIMITED), _RATE_LIMITED),
        (ResolverCallError("refused", category=_NETWORK), _NETWORK),
        (RuntimeError("모델 미설정"), "RESOLVER_FAILED"),
    ],
)
def test_resolver_failure_keeps_normalization(
    client: TestClient, stub: StubFactory, failure: Exception, expected: str
) -> None:
    """해석이 죽어도 200 이다. 이 응답의 search_tokens 로 BM25 fallback 이 이어진다."""
    stub(failure)

    response = client.post("/query/resolve", json={"query": RAW_QUERY})

    assert response.status_code == 200
    body = response.json()
    assert body["resolution"] is None
    assert body["error"]["category"] == expected
    assert body["normalization"]["search_tokens"]


def test_schema_invalid_is_reported_not_raised(client: TestClient, stub: StubFactory) -> None:
    stub("이건 JSON 이 아니다")

    body = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    assert body["resolution"] is None
    assert body["error"]["category"] == "RESOLVER_SCHEMA_INVALID"
    assert body["normalization"]["search_tokens"]


def test_unclassified_schema_error_is_reported(client: TestClient, stub: StubFactory) -> None:
    stub(ResolverSchemaInvalidError("schema 와 맞지 않는다"))

    body = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    assert body["error"]["category"] == "RESOLVER_SCHEMA_INVALID"


@pytest.mark.parametrize("query", ["   ", "!!!???"])
def test_unnormalizable_query_is_400(client: TestClient, stub: StubFactory, query: str) -> None:
    """지문을 만들 수 없으면 검색 자체가 성립하지 않는다. 해석 실패와 다르다."""
    stub(_valid_payload())

    response = client.post("/query/resolve", json={"query": query})

    assert response.status_code == 400


def test_blank_queries_all_get_the_same_status(client: TestClient) -> None:
    """`""` 와 `"   "` 는 같은 종류의 실패다.

    pydantic 이 `""` 만 먼저 막으면 422 가 나가고, BE 번역기는 422 를 `RESOLVER_FAILED`(503)
    로 분류한다. 같은 "정규화 불가" 입력이 한쪽은 서버 장애로, 한쪽은 입력 오류로 보이게 된다.
    """
    for query in ("", "   ", "	"):
        response = client.post("/query/resolve", json={"query": query})

        assert response.status_code == 400, f"{query!r} 가 400 이 아니다"


def test_backends_agree_on_categories() -> None:
    """두 백엔드가 다른 문자열을 쓰면 BE 가 한쪽을 RESOLVER_FAILED 로 뭉갠다."""
    assert _NETWORK == _OLLAMA_NETWORK


def test_error_response_carries_no_vendor_detail(client: TestClient, stub: StubFactory) -> None:
    """FRD v3.1 §6.4 — 엔드포인트 URL·응답 본문이 응답으로 새면 안 된다."""
    secret = "http://gms.internal/v1/chat"
    stub(ResolverCallError(f"GMS 호출이 실패했다 ({secret}): boom", category=_NETWORK))

    body = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    assert body["error"]["category"] == _NETWORK
    assert secret not in response_text(body)
    assert "boom" not in response_text(body)


def response_text(body: dict) -> str:
    return json.dumps(body, ensure_ascii=False)


# ── 질의 임베딩 (S15P21A501-164) ────────────────────────────────────────


def test_response_carries_the_query_embedding(
    client: TestClient, stub: StubFactory, encoder: StubEncoder
) -> None:
    stub(_valid_payload())

    body = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    assert len(body["embedding"]) == SCENE_EMBEDDING_DIMENSION
    assert body["embedding_model_version"] == "stub/embedding@0"
    assert body["embedding_error"] is None


def test_embedding_uses_the_raw_query_with_the_model_prefix(
    client: TestClient, stub: StubFactory, encoder: StubEncoder
) -> None:
    """정규화 질의를 넣으면 색인 측(자연어 문장)과 입력 분포가 어긋난다."""
    stub(_valid_payload())

    body = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    assert encoder.texts == [get_settings().embedding_query_prefix + RAW_QUERY]
    assert body["normalization"]["normalized_query"] not in encoder.texts[0]


@pytest.mark.parametrize(
    "failure",
    [
        EmbeddingCallError("CUDA 런타임 오류"),
        EmbeddingModelUnavailableError("가중치를 못 받았다"),
        RuntimeError("분류되지 않은 실패"),
    ],
)
def test_embedding_failure_keeps_the_rest_of_the_response(
    client: TestClient,
    stub: StubFactory,
    broken_encoder: Callable[[Exception], StubEncoder],
    failure: Exception,
) -> None:
    """FRD v3.1 §6.2 — "텍스트 의미 검색 실패 → 단어 검색과 사용 가능한 신호로 결과 제공".

    임베딩이 죽어도 200 이고, 이 응답의 `search_tokens` 로 BM25 가 이어진다.
    해석 결과도 함께 살아 나온다 — 두 실패는 별개 축이다.
    """
    stub(_valid_payload())
    broken_encoder(failure)

    response = client.post("/query/resolve", json={"query": RAW_QUERY})

    assert response.status_code == 200
    body = response.json()
    assert body["embedding"] is None
    assert body["embedding_model_version"] is None
    assert body["embedding_error"]["category"] == "EMBEDDING_FAILED"
    assert body["normalization"]["search_tokens"]
    assert body["resolution"]["intent"] == "scene_search"
    assert body["error"] is None


def test_resolver_failure_does_not_kill_the_embedding(
    client: TestClient, stub: StubFactory, encoder: StubEncoder
) -> None:
    """해석과 임베딩은 독립이다. 하나가 죽었다고 다른 하나를 버리지 않는다."""
    stub(ResolverCallError("timed out", category=_TIMEOUT))

    body = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    assert body["resolution"] is None
    assert body["error"]["category"] == _TIMEOUT
    assert len(body["embedding"]) == SCENE_EMBEDDING_DIMENSION
    assert body["embedding_error"] is None


def test_embedding_vector_is_normalized(
    client: TestClient, stub: StubFactory, encoder: StubEncoder
) -> None:
    """색인 벡터와 같은 크기 규칙이어야 코사인 순위가 성립한다."""
    stub(_valid_payload())

    body = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    norm = sum(value * value for value in body["embedding"]) ** 0.5
    assert abs(norm - 1.0) < 1e-9


def test_unnormalizable_query_never_reaches_the_model(
    client: TestClient, stub: StubFactory, encoder: StubEncoder
) -> None:
    """400 이 될 질의로 GPU 를 쓰지 않는다."""
    stub(_valid_payload())

    client.post("/query/resolve", json={"query": "   "})

    assert encoder.texts == []


def test_warm_up_loads_the_weights_before_the_first_search(encoder: StubEncoder) -> None:
    """**리졸버 배포 단위는 `jobs.warm_up()` 을 타지 않는다** — `job_poll_enabled` 가
    꺼져 있기 때문이다. 그러면 부팅 후 첫 검색이 1.7GB 로딩을 물고 동기 예산을 날린다.
    """
    assert query_api.warm_query_encoder() is True
    assert encoder.texts, "워밍업이 인코더를 부르지 않았다"


def test_warm_up_failure_does_not_stop_the_process(
    broken_encoder: Callable[[Exception], StubEncoder],
) -> None:
    """가중치가 없어도 리졸버는 뜬다. 검색은 BM25 로 이어진다 (`ai/AGENTS.md`)."""
    broken_encoder(EmbeddingModelUnavailableError("캐시 볼륨이 안 붙었다"))

    assert query_api.warm_query_encoder() is False


def test_embedding_error_carries_no_vendor_detail(
    client: TestClient, stub: StubFactory, broken_encoder: Callable[[Exception], StubEncoder]
) -> None:
    """FRD v3.1 §6.4 — 모델 경로·내부 메시지가 응답으로 새면 안 된다."""
    secret = "/runpod-volume/models/arctic-ko"
    stub(_valid_payload())
    broken_encoder(EmbeddingModelUnavailableError(f"가중치를 못 받았다: {secret}"))

    body = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    assert body["embedding_error"]["category"] == "EMBEDDING_FAILED"
    assert secret not in response_text(body)
