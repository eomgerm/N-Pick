"""검색 서비스가 부르는 질의 해석 HTTP 표면 (S15P21A501-45).

한 번의 요청으로 정규화와 해석을 함께 돌려준다. `docs/architecture/02-container.md`
요소 표가 질의 리졸버의 책임을 "질의 구조화, 질의 임베딩, Kiwi 형태소 토큰화" 하나로
묶어 두었고, 검색은 동기 예산 안에서 끝나야 해서 왕복을 늘리지 않는다.

**해석 실패를 HTTP 오류로 만들지 않는다.** FRD v3.1 §6.2 는 "AI 해석 실패·시간 초과"
에서 원 검색어의 단어 검색으로 전환하라고 하는데, 그 BM25 에는 정규화가 만든
`search_tokens` 가 필요하다. 정규화가 성공했다면 그 결과는 반드시 호출부에 닿아야
하므로 200 으로 내려보내고 `error` 에 사유를 담는다. 400 은 정규화 자체가 불가능할
때만 쓴다.

정규화와 해석은 순차가 아니라 **같은 원문에서 각각 출발한다.** 해석에 정규화 질의를
넣으면 `query_span` 이 원문과 어긋나 explicit anchor 가 전부 강등된다.
"""

import logging
from functools import lru_cache

from pydantic import BaseModel

from npick_worker.query_embedding import QueryEmbedding, embed_query
from npick_worker.query_normalization import normalize
from npick_worker.query_resolver import (
    QueryResolver,
    ResolverCallError,
    ResolverSchemaInvalidError,
    ValidatedResolution,
    get_default_config,
    resolve_query,
)
from npick_worker.query_resolver.gms_backend import GmsResolver
from npick_worker.query_resolver.ollama_backend import OllamaResolver
from npick_worker.query_resolver.validator import RESOLVER_SCHEMA_INVALID
from npick_worker.settings import Settings, get_settings
from npick_worker.text_embedding.encoder import TextEncoder

logger = logging.getLogger(__name__)

#: 해석 경로 전체가 죽었을 때 쓰는 사유. 백엔드가 분류하지 못한 실패다.
RESOLVER_FAILED = "RESOLVER_FAILED"

#: 질의 임베딩이 죽었을 때 쓰는 사유. **한 종류뿐이다.**
#:
#: 어댑터는 호출 실패(`EmbeddingCallError`)와 가중치 부재(`EmbeddingModelUnavailableError`)
#: 를 가르지만 리졸버는 동기 호출 전용이고 재시도가 없다(`02-container.md`). 둘 다 호출부가
#: 할 일이 같다 — BM25 로 이어간다. BE 가 실제로 갈라서 처리할 일이 생기면 그때 쪼갠다.
#:
#: **사용자에게 보이는 이름은 이것이 아니다.** `docs/contracts/web-api.md` §5.1 이
#: 검색 응답의 `degraded_reasons` 값을 `dense_unavailable` 로 이미 닫아 두었고, 그 값을
#: 붙이는 것은 BE(`SearchDegradedReason`, S15P21A501-53)다. 이쪽은 워커가 BE 에 말하는
#: category 이고 `RESOLVER_*` 가 `QueryResolverErrorCode` 로 옮겨지는 것과 같은 층이다.
EMBEDDING_FAILED = "EMBEDDING_FAILED"


class QueryNotNormalizableError(ValueError):
    """정규화 자체가 불가능한 질의. 라우터가 이것만 400 으로 바꾼다.

    맨 `ValueError` 를 쓰지 않는 이유는 그 타입이 너무 넓기 때문이다 — pydantic 의
    `ValidationError` 와 `ResolverSchemaInvalidError` 가 모두 `ValueError` 라, 라우터가
    `ValueError` 를 잡으면 설정 오류나 우리 쪽 버그까지 "질의가 잘못됐다" 로 사용자에게
    돌아간다. 400 을 낼 자격은 정규화 실패에만 준다.
    """


class QueryResolveRequest(BaseModel):
    """사용자가 친 원문 하나. 정규화 질의를 보내면 안 된다.

    **`min_length` 을 걸지 않는다.** 걸면 `""` 만 pydantic 이 422 로 막고 `"   "` 는 통과해
    400 이 된다. 둘 다 "정규화할 수 없는 질의" 인데 호출부에서 422→`RESOLVER_FAILED`(503),
    400→`QUERY_NOT_NORMALIZABLE` 로 갈려 같은 입력이 장애로도 보이고 입력 오류로도 보인다.
    빈 값 판정은 정규화 한 곳에서만 한다.
    """

    query: str


class Normalization(BaseModel):
    """지문과 BM25 의 재료. 해석이 실패해도 이 값은 항상 채운다."""

    normalized_query: str
    search_tokens: tuple[str, ...]
    normalization_version: str


class ResolverError(BaseModel):
    """해석 실패 사유. 호출부가 degraded 사유로 기록한다 (FRD v3.1 §7.2)."""

    #: `RESOLVER_TIMEOUT` · `RESOLVER_SCHEMA_INVALID` · `RESOLVER_RATE_LIMITED`
    #: · `RESOLVER_NETWORK` · `RESOLVER_FAILED`
    #:
    #: 값은 백엔드의 `ResolverCallError.category` 상수와 같다. BE 의
    #: `QueryResolverErrorCode` enum 이름이 여기에 1:1 로 맞춰져 있으므로 문자열을 바꾸면
    #: 양쪽을 같이 고쳐야 한다.
    category: str


class EmbeddingError(BaseModel):
    """질의 임베딩 실패 사유. 호출부가 degraded 사유로 기록한다 (FRD v3.1 §7.2).

    `ResolverError` 와 타입을 나눈 이유는 **두 실패가 독립이기 때문**이다. 해석은
    성공하고 임베딩만 죽을 수 있고 그 반대도 된다. 한 필드에 섞으면 호출부가 어느
    채널을 포기해야 하는지 알 수 없다 — 해석이 죽으면 지문·필터를 잃고, 임베딩이
    죽으면 dense 채널만 잃는다.
    """

    #: 지금은 `EMBEDDING_FAILED` 하나뿐이다.
    category: str


class QueryResolveResponse(BaseModel):
    """정규화는 항상, 해석과 임베딩은 되는 만큼 채운다.

    `resolution` 이 `None` 이면 `error` 가 있고, 그 반대도 성립한다. `embedding` 과
    `embedding_error` 도 같은 관계이되 **해석과는 독립된 축**이다.
    """

    normalization: Normalization
    resolution: ValidatedResolution | None = None
    findings: tuple[dict[str, str], ...] = ()
    resolution_schema_version: str | None = None
    prompt_version: str | None = None
    model_version: str | None = None
    error: ResolverError | None = None

    #: dense 검색 채널의 질의 측 재료 (S15P21A501-164). 색인 측 `scene.embedding` 과
    #: 같은 모델·차원이라 그대로 코사인 비교가 된다. 실패하면 `None` 이고 호출부는
    #: BM25 로 이어간다 (FRD v3.1 §6.2).
    embedding: tuple[float, ...] | None = None
    #: 그 벡터를 만든 가중치. 색인 측 값과 다르면 유사도가 무의미하므로 호출부가
    #: 대조할 수 있어야 한다.
    embedding_model_version: str | None = None
    embedding_error: EmbeddingError | None = None


@lru_cache(maxsize=1)
def _resolver() -> QueryResolver:
    """설정이 고른 백엔드 하나. 요청마다 만들지 않는다.

    모델이 없어도 워커는 기동한다 (`ai/AGENTS.md`). 설정이 비어 있으면 여기서
    `ValueError` 가 나고, 그건 해석 실패로 분류되어 검색은 BM25 로 이어진다.
    """
    settings: Settings = get_settings()
    params = get_default_config().call
    if settings.resolver_backend == "gms":
        return GmsResolver(
            base_url=settings.gms_base_url,
            api_key=settings.gms_api_key.get_secret_value(),
            model=settings.gms_model,
            params=params,
            json_mode=settings.gms_json_mode,
        )
    return OllamaResolver(base_url=settings.ollama_url, model=settings.ollama_model, params=params)


def _encoder() -> TextEncoder:
    """프로세스가 공유하는 인코더 하나. **색인 측과 같은 인스턴스다.**

    `shared_encoder()` 가 이미 `lru_cache` 라 여기서 또 캐시하지 않는다. 이 함수가
    따로 있는 이유는 테스트가 갈아 끼울 자리를 주기 위해서다 — 그게 없으면 이 파일의
    모든 테스트가 1.7GB 가중치를 내려받는다.

    지연 임포트다. `sentence_transformers` 는 함수 안에서 끌어오지만 이 모듈은 앱
    기동 경로에 있어 임포트 시점에 어댑터 모듈까지 끌 이유가 없다.
    """
    from npick_worker.text_embedding.sentence_transformers_backend import shared_encoder

    return shared_encoder()


def warm_query_encoder() -> bool:
    """가중치를 미리 올린다. 기동 시 1회 부른다. 성공 여부를 돌려준다.

    **리졸버 배포 단위는 `jobs.warm_up()` 을 타지 않는다.** 그쪽은 `job_poll_enabled`
    가 켜진 프로세스에서만 도는데 리졸버는 폴링하지 않는다(`app.lifespan`). 어댑터는
    인스턴스만 만들고 가중치는 첫 `encode` 에서 올라가므로, 워밍업이 없으면 **부팅 후
    첫 검색**이 1.7GB 로딩과 CUDA 컨텍스트 초기화를 통째로 물고 동기 예산(p95 10초)을
    날린다. 이후 호출은 실측 p95 110~120ms 다(S15P21A501-175).

    `encode` 로 깨운다. `SentenceTransformerEncoder.warm_up()` 을 부르려면 Protocol 에
    없는 메서드를 `getattr` 로 찾아야 하는데, 어차피 가중치를 올리는 것은 `encode` 이고
    `eval/text_embedding/measure.py` 도 같은 방법을 쓴다.

    **실패해도 예외를 올리지 않는다.** 가중치가 없어도 리졸버는 떠야 한다 —
    `ai/AGENTS.md` 의 "GPU 없이도 워커가 기동하는 성질" 과 같은 요구이고, 그런 프로세스의
    검색은 임베딩 없이 BM25 로 이어진다(FRD v3.1 §6.2).
    """
    try:
        _encoder().encode([get_settings().embedding_query_prefix + "워밍업"])
    except Exception as exc:
        logger.warning(
            "질의 임베딩 워밍업 실패: %s: %s. 이 프로세스의 검색은 dense 채널 없이 돈다",
            type(exc).__name__,
            exc,
        )
        return False
    return True


def _degraded(
    base: QueryResolveResponse, category: str, cause: Exception | None
) -> QueryResolveResponse:
    """해석 실패를 응답으로 만든다. 정규화와 임베딩은 살려서 보낸다.

    **응답에는 `category` 만 싣는다.** 백엔드 예외 메시지에는 GMS 엔드포인트 URL 과 응답 본문
    조각이 들어 있어(`gms_backend.py`), 그대로 내보내면 FRD v3.1 §6.4 의 "서버 절대 경로·전체
    민감 원문을 응답에 노출하지 않는다" 를 어긴다. 진단용 상세는 워커 로그에만 남긴다.
    """
    if cause is not None:
        logger.warning("질의 해석 실패 category=%s: %s", category, cause)
    return base.model_copy(update={"error": ResolverError(category=category)})


def _embed(raw_query: str) -> QueryEmbedding | None:
    """dense 채널의 질의 벡터. 실패하면 `None` 이고 검색은 BM25 로 이어진다.

    FRD v3.1 §6.2 가 "텍스트 의미 검색 실패 → 단어 검색과 사용 가능한 신호로 결과 제공"
    으로 정한다. 그래서 **모든 예외를 삼킨다** — 어댑터의 일시 오류(호출 실패·가중치
    부재)뿐 아니라 설정 오류와 우리 쪽 버그까지다. 버그를 조용히 넘기면 모든 검색이
    dense 채널 없이 돌면서 아무도 모르게 되므로 스택트레이스를 남긴다(`_resolver` 의
    같은 판단).

    `ValueError` 도 여기서 잡힌다. 정규화가 이미 빈 질의를 400 으로 걸러 내므로 남는
    것은 차원 불일치·0 벡터 같은 설정·모델 문제이고, 그것으로 검색 전체를 죽일 이유가
    없다 — 다만 로그에는 남아야 한다.
    """
    encoder = _encoder()

    # **가중치가 준비되기 전에는 시도하지 않는다.** 그냥 부르면 `encode` 가
    # `_ensure_loaded()` 로 들어가 **요청 스레드가 1.7GB 로딩을 기다린다** — 워밍업이
    # 상한을 넘겨 백그라운드에서 계속 도는 동안 들어온 요청이 전부 그렇게 된다.
    # §6.2 가 정한 것은 "의미 검색 실패 → 단어 검색으로 결과 제공" 이지 "준비될
    # 때까지 대기" 가 아니다. 어댑터의 single-flight 락이 로딩 중복은 막지만, 락을
    # 기다리는 것도 기다리는 것이라 여기서 먼저 끊는다.
    #
    # `getattr` 인 이유는 `TextEncoder` Protocol 에 `is_ready` 가 없기 때문이다.
    # 상태를 말하지 못하는 구현은 **준비된 것으로 본다** — 그런 어댑터는 로딩이
    # 지연 단계가 아니거나(원격 API) 생성 시점에 이미 올라와 있다.
    if not getattr(encoder, "is_ready", True):
        logger.info("임베딩 가중치가 아직 준비되지 않았다. 이 검색은 BM25 로만 돈다")
        return None

    try:
        return embed_query(raw_query, encoder=encoder)
    except Exception:
        logger.exception("질의 임베딩이 실패했다. dense 채널 없이 검색한다")
        return None


def resolve(request: QueryResolveRequest) -> QueryResolveResponse:
    """정규화는 반드시, 해석과 임베딩은 되는 만큼.

    정규화가 실패하면 `QueryNotNormalizableError` 로 올린다 — 지문을 만들 수 없어 검색
    자체가 성립하지 않는다. 라우터가 그 타입만 400 으로 바꾼다. 그 뒤로는 어떤 실패도
    200 이다.

    **해석과 임베딩은 순차가 아니라 각자 원문에서 출발한다.** 둘 다 원문을 쓰고 서로의
    결과를 보지 않는다 — 하나가 죽어도 다른 하나가 살아 나가야 하고, 정규화 질의를
    넣으면 해석은 `query_span` 이 어긋나고 임베딩은 색인 측과 입력 분포가 어긋난다.
    """
    try:
        normalized = normalize(request.query)
    except ValueError as exc:
        raise QueryNotNormalizableError(str(exc)) from exc

    # 임베딩 결과를 먼저 담아 둔다. 아래 해석이 어느 갈래로 빠지든 이 값은 그대로
    # 실려 나간다 — 두 축이 독립이라는 것을 구조로 만든 자리다.
    embedding = _embed(request.query)
    base = QueryResolveResponse(
        normalization=Normalization(
            normalized_query=normalized.normalized_query,
            search_tokens=normalized.search_tokens,
            normalization_version=normalized.normalization_version,
        ),
        embedding=embedding.vector if embedding is not None else None,
        embedding_model_version=embedding.model_version if embedding is not None else None,
        embedding_error=None
        if embedding is not None
        else EmbeddingError(category=EMBEDDING_FAILED),
    )

    try:
        result = resolve_query(request.query, _resolver())
    except ResolverCallError as exc:
        return _degraded(base, exc.category, exc)
    except ResolverSchemaInvalidError as exc:
        return _degraded(base, RESOLVER_SCHEMA_INVALID, exc)
    except Exception:  # 해석 실패가 검색을 죽이지 않게 한다 (FRD v3.1 §6.2)
        # 분류되지 않은 실패다. 여기에는 모델 미설정 같은 설정 문제와 **우리 쪽 버그**가
        # 함께 걸린다. 버그를 조용히 degraded 로 넘기면 모든 검색이 해석 없이 돌면서
        # 아무도 모르게 되므로 스택트레이스를 남긴다.
        logger.exception("질의 해석이 분류되지 않은 이유로 실패했다")
        return _degraded(base, RESOLVER_FAILED, None)

    return base.model_copy(
        update={
            "resolution": result.resolution,
            "findings": tuple(
                {"path": f.path, "action": f.action, "reason": f.reason} for f in result.findings
            ),
            "resolution_schema_version": result.resolution_schema_version,
            "prompt_version": result.prompt_version,
            "model_version": result.model_version,
        }
    )
