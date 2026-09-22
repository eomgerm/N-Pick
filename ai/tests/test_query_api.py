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
from npick_worker.query_api import (
    MAX_TOKENIZE_ITEMS,
    MAX_TOKENIZE_TEXT_LENGTH,
)
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


# ── POST /query/tokenize (S15P21A501-205) ──────────────────────────────────
#
# 확장어를 후보 조회에 넣으려면 **규칙 적용 뒤** 토큰화해야 한다 (S15P21A501-48 계약
# 7). 교정(`patch_parse`)으로 검수자가 넣은 확장어는 리졸버가 모르는 값이라
# `/query/resolve` 응답에 토큰을 실어 보내는 방식으로는 덮이지 않는다.
#
# 검사하는 것은 셋이다.
#
# 1. 색인 측과 **같은 토큰**인가 — 별칭·불용어·정렬이 걸리면 안 된다. 색인이 하지
#    않는 변형을 질의에만 걸면 오류 없이 0 건이 된다.
# 2. 토큰이 0 개인 항목이 요청 전체를 깨뜨리지 않는가 — 계약 9 는 토큰화 실패를
#    degraded 로도 치지 않는다.
# 3. `normalization_version` 이 `/query/resolve` 의 값과 같은가.


def test_tokenize_returns_one_token_list_per_input_in_order(client: TestClient) -> None:
    response = client.post("/query/tokenize", json={"texts": ["귀성객", "부산 침수", "서울역"]})

    assert response.status_code == 200
    tokens = response.json()["tokens"]
    # 항목별 결과를 **위치로** 맞춘다. 입력을 되돌려 주지 않으므로 이 정렬이 계약이다.
    assert len(tokens) == 3
    assert tokens[0] == ["귀성객/NNG"]
    assert tokens[1] == ["부산/NNP", "침수/NNG"]
    # 색인 측과 같은 분절이다 — 사용자 사전의 「서울」 때문에 「서울역」 이 쪼개진다.
    # 문서 쪽 ocr 단계가 같은 경로를 쓰므로 이렇게 쪼개져야 맞는다.
    assert tokens[2] == ["서울/NNP", "역/NNG"]


def test_tokenize_item_without_content_tokens_is_empty_not_error(client: TestClient) -> None:
    """토큰이 0 개인 항목은 빈 목록이고 요청 전체는 200 이다.

    확장어 한 건이 기호뿐이라고 검색을 끊으면 안 된다 — 계약 9 는 토큰화 실패를
    degraded 로도 치지 않는다. `normalize()` 를 재사용하지 않는 이유가 이것이다.
    그쪽은 내용어가 없으면 `ValueError` 를 던진다.
    """
    response = client.post("/query/tokenize", json={"texts": ["···", "귀성객"]})

    assert response.status_code == 200
    assert response.json()["tokens"] == [[], ["귀성객/NNG"]]


def test_tokenize_applies_no_alias(client: TestClient) -> None:
    """색인 측이 하지 않는 변형은 걸지 않는다 (계약 7).

    `normalize()` 의 지문(`normalized_query`)은 별칭을 걸어 「서울시」를 「서울」로
    바꾸지만, 색인 토큰은 그러지 않는다. 여기서 별칭이 걸리면 질의 토큰만 색인과
    어긋나 오류 없이 0 건이 된다.
    """
    tokens = client.post("/query/tokenize", json={"texts": ["서울시"]}).json()["tokens"]

    assert tokens == [["서울시/NNP"]]


def test_tokenize_version_matches_resolve(client: TestClient, stub: StubFactory) -> None:
    """두 라우트가 같은 규칙으로 토큰을 만든다는 것을 버전으로 고정한다."""
    stub(_valid_payload())

    tokenize = client.post("/query/tokenize", json={"texts": ["귀성객"]}).json()
    resolve = client.post("/query/resolve", json={"query": RAW_QUERY}).json()

    assert tokenize["normalization_version"] == resolve["normalization"]["normalization_version"]


def test_tokenize_accepts_request_at_the_limit(client: TestClient) -> None:
    """경계값은 받는다. 상한은 「이하」 다.

    초과 케이스만 두면 상한이 63 이나 199 로 밀려도 테스트가 통과한다.
    """
    at_limit = ["귀성객"] * (MAX_TOKENIZE_ITEMS - 1) + ["가" * MAX_TOKENIZE_TEXT_LENGTH]

    response = client.post("/query/tokenize", json={"texts": at_limit})

    assert response.status_code == 200
    assert len(response.json()["tokens"]) == MAX_TOKENIZE_ITEMS


def test_tokenize_rejects_too_many_items(client: TestClient) -> None:
    """동기 검색 예산 안에서 도는 경로다. 상한은 pydantic 이 막는다.

    호출부는 이때 확장어 없이 검색을 이어간다 (계약 9).
    """
    response = client.post("/query/tokenize", json={"texts": ["귀성객"] * (MAX_TOKENIZE_ITEMS + 1)})

    assert response.status_code == 422


def test_tokenize_rejects_too_long_text(client: TestClient) -> None:
    """항목 하나가 길어도 Kiwi 비용은 똑같이 는다. 개수 상한만으로는 못 막는다."""
    response = client.post(
        "/query/tokenize", json={"texts": ["가" * (MAX_TOKENIZE_TEXT_LENGTH + 1)]}
    )

    assert response.status_code == 422
