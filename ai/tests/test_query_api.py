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
from collections.abc import Callable

import pytest
from fastapi.testclient import TestClient

from npick_worker import query_api
from npick_worker.query_resolver import ResolverCallError, ResolverSchemaInvalidError

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
                    "query_span": {"start": 8, "end": 10},
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
        (ResolverCallError("timed out", category="RESOLVER_TIMEOUT"), "RESOLVER_TIMEOUT"),
        (ResolverCallError("429", category="RESOLVER_RATE_LIMITED"), "RESOLVER_RATE_LIMITED"),
        (ResolverCallError("refused", category="RESOLVER_NETWORK_ERROR"), "RESOLVER_NETWORK_ERROR"),
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


def test_empty_query_is_rejected_by_validation(client: TestClient) -> None:
    response = client.post("/query/resolve", json={"query": ""})

    assert response.status_code == 422
