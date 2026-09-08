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

#: 해석 경로 전체가 죽었을 때 쓰는 사유. 백엔드가 분류하지 못한 실패다.
RESOLVER_FAILED = "RESOLVER_FAILED"


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
    #: · `RESOLVER_NETWORK_ERROR` · `RESOLVER_FAILED`
    category: str
    message: str


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


def resolve(request: QueryResolveRequest) -> QueryResolveResponse:
    """정규화는 반드시, 해석은 되는 만큼.

    정규화가 실패하면 `ValueError` 를 그대로 올린다 — 지문을 만들 수 없어 검색 자체가
    성립하지 않는다. 라우터가 400 으로 바꾼다.
    """
    normalized = normalize(request.query)
    normalization = Normalization(
        normalized_query=normalized.normalized_query,
        search_tokens=normalized.search_tokens,
        normalization_version=normalized.normalization_version,
    )

    try:
        result = resolve_query(request.query, _resolver())
    except ResolverCallError as exc:
        return QueryResolveResponse(
            normalization=normalization,
            error=ResolverError(category=exc.category, message=str(exc)),
        )
    except ResolverSchemaInvalidError as exc:
        return QueryResolveResponse(
            normalization=normalization,
            error=ResolverError(category=RESOLVER_SCHEMA_INVALID, message=str(exc)),
        )
    except Exception as exc:  # 해석 실패가 검색을 죽이지 않게 한다 (FRD v3.1 §6.2)
        # 모델 미설정 등 분류되지 않은 실패다. 원문 대신 종류만 남긴다 (§6.4).
        return QueryResolveResponse(
            normalization=normalization,
            error=ResolverError(category=RESOLVER_FAILED, message=type(exc).__name__),
        )

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
