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

from pydantic import BaseModel, Field

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

logger = logging.getLogger(__name__)

#: 해석 경로 전체가 죽었을 때 쓰는 사유. 백엔드가 분류하지 못한 실패다.
RESOLVER_FAILED = "RESOLVER_FAILED"


class QueryNotNormalizableError(ValueError):
    """정규화 자체가 불가능한 질의. 라우터가 이것만 400 으로 바꾼다.

    맨 `ValueError` 를 쓰지 않는 이유는 그 타입이 너무 넓기 때문이다 — pydantic 의
    `ValidationError` 와 `ResolverSchemaInvalidError` 가 모두 `ValueError` 라, 라우터가
    `ValueError` 를 잡으면 설정 오류나 우리 쪽 버그까지 "질의가 잘못됐다" 로 사용자에게
    돌아간다. 400 을 낼 자격은 정규화 실패에만 준다.
    """


class QueryResolveRequest(BaseModel):
    """사용자가 친 원문 하나. 정규화 질의를 보내면 안 된다."""

    query: str = Field(min_length=1)


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


class QueryResolveResponse(BaseModel):
    """정규화는 항상, 해석은 성공했을 때만 채운다.

    `resolution` 이 `None` 이면 `error` 가 있고, 그 반대도 성립한다.

    TODO(S15P21A501-164): 질의 임베딩 필드가 여기 들어온다. 모델과 차원은
    `S15P21A501-100` 이 확정하기 전까지 정하지 않는다 — 색인 측과 다른 모델을 쓰면
    유사도가 무의미해진다.
    """

    normalization: Normalization
    resolution: ValidatedResolution | None = None
    findings: tuple[dict[str, str], ...] = ()
    resolution_schema_version: str | None = None
    prompt_version: str | None = None
    model_version: str | None = None
    error: ResolverError | None = None


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


def _degraded(
    normalization: Normalization, category: str, cause: Exception | None
) -> QueryResolveResponse:
    """해석 실패를 응답으로 만든다. 정규화는 살려서 보낸다.

    **응답에는 `category` 만 싣는다.** 백엔드 예외 메시지에는 GMS 엔드포인트 URL 과 응답 본문
    조각이 들어 있어(`gms_backend.py`), 그대로 내보내면 FRD v3.1 §6.4 의 "서버 절대 경로·전체
    민감 원문을 응답에 노출하지 않는다" 를 어긴다. 진단용 상세는 워커 로그에만 남긴다.
    """
    if cause is not None:
        logger.warning("질의 해석 실패 category=%s: %s", category, cause)
    return QueryResolveResponse(normalization=normalization, error=ResolverError(category=category))


def resolve(request: QueryResolveRequest) -> QueryResolveResponse:
    """정규화는 반드시, 해석은 되는 만큼.

    정규화가 실패하면 `QueryNotNormalizableError` 로 올린다 — 지문을 만들 수 없어 검색
    자체가 성립하지 않는다. 라우터가 그 타입만 400 으로 바꾼다.
    """
    try:
        normalized = normalize(request.query)
    except ValueError as exc:
        raise QueryNotNormalizableError(str(exc)) from exc
    normalization = Normalization(
        normalized_query=normalized.normalized_query,
        search_tokens=normalized.search_tokens,
        normalization_version=normalized.normalization_version,
    )

    try:
        result = resolve_query(request.query, _resolver())
    except ResolverCallError as exc:
        return _degraded(normalization, exc.category, exc)
    except ResolverSchemaInvalidError as exc:
        return _degraded(normalization, RESOLVER_SCHEMA_INVALID, exc)
    except Exception:  # 해석 실패가 검색을 죽이지 않게 한다 (FRD v3.1 §6.2)
        # 분류되지 않은 실패다. 여기에는 모델 미설정 같은 설정 문제와 **우리 쪽 버그**가
        # 함께 걸린다. 버그를 조용히 degraded 로 넘기면 모든 검색이 해석 없이 돌면서
        # 아무도 모르게 되므로 스택트레이스를 남긴다.
        logger.exception("질의 해석이 분류되지 않은 이유로 실패했다")
        return _degraded(normalization, RESOLVER_FAILED, None)

    return QueryResolveResponse(
        normalization=normalization,
        resolution=result.resolution,
        findings=tuple(
            {"path": f.path, "action": f.action, "reason": f.reason} for f in result.findings
        ),
        resolution_schema_version=result.resolution_schema_version,
        prompt_version=result.prompt_version,
        model_version=result.model_version,
    )
