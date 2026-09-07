"""Query Resolver — 한국어 질의를 구조화한다 (FRD F-04~06).

프롬프트 구성 → LLM 호출 → 검증을 제공한다. 검색 정규화·규칙 적용·기록은 검색
호출부 책임이다(F-05, §7.2). 배포 경계는 docs/architecture/02-container.md를 따른다.
ResolverCallError와 ResolverSchemaInvalidError는 모듈 오류 계약이다. 호출부는 §6.2에
따라 원 검색어의 BM25로 전환하고 누락을 안내하며 동기 AI 재시도를 하지 않는다.
"""

from dataclasses import dataclass

from npick_worker.query_resolver.config import (
    DEFAULT_CONFIG_PATH,
    CallParams,
    QueryResolverConfig,
    get_default_config,
    load_config,
)
from npick_worker.query_resolver.prompt import render_system_prompt, render_user_prompt
from npick_worker.query_resolver.resolver import QueryResolver, ResolverCallError
from npick_worker.query_resolver.schema import (
    SCHEMA_VERSION,
    Classification,
    DateField,
    DateWindow,
    Entity,
    IncidentName,
    Location,
    QuerySpan,
    RawResolution,
    ValidatedResolution,
    empty_resolution,
)
from npick_worker.query_resolver.validator import (
    RESOLVER_SCHEMA_INVALID,
    AnchorFinding,
    ResolverSchemaInvalidError,
    ValidationOutcome,
    parse_raw,
    validate,
)

__all__ = [
    "DEFAULT_CONFIG_PATH",
    "RESOLVER_SCHEMA_INVALID",
    "SCHEMA_VERSION",
    "AnchorFinding",
    "CallParams",
    "Classification",
    "DateField",
    "DateWindow",
    "Entity",
    "IncidentName",
    "Location",
    "QueryResolver",
    "QueryResolverConfig",
    "QuerySpan",
    "RawResolution",
    "ResolutionResult",
    "ResolverCallError",
    "ResolverSchemaInvalidError",
    "ValidatedResolution",
    "ValidationOutcome",
    "empty_resolution",
    "get_default_config",
    "load_config",
    "parse_raw",
    "render_system_prompt",
    "render_user_prompt",
    "resolve_query",
    "validate",
]


@dataclass(frozen=True, slots=True)
class ResolutionResult:
    """해석 결과와 §7.2 기록을 지원하는 모듈 반환 메타데이터.

    버전이 셋인 이유는 셋 다 결과를 바꾸기 때문이다 — schema 가 바뀌면 필드가,
    프롬프트가 바뀌면 해석이, 모델이 바뀌면 판단이 달라진다. 하나라도 빠지면
    "같은 입력에 왜 다른 결과가 나왔나" 를 나중에 설명할 수 없다.
    """

    resolution: ValidatedResolution
    #: 검증이 무엇을 바꿨는지. 호출자가 기록할 변경 내역이다.
    findings: tuple[AnchorFinding, ...]
    #: `resolution_schema_version`
    resolution_schema_version: str
    #: `prompt_version`
    prompt_version: str
    #: `model_version`
    model_version: str


def resolve_query(
    query: str,
    resolver: QueryResolver,
    cfg: QueryResolverConfig | None = None,
) -> ResolutionResult:
    """원문 질의 하나를 검증된 resolution 으로 바꾼다.

    `query` 는 **사용자가 친 원문**이다. canonical query 를 넣지 않는다 —
    `query_span` 이 원문 기준이라(`FRD F-05`) 정규화된 문자열을 넣으면 모든
    explicit anchor 가 강등된다.

    `resolver` 를 인자로 받는 이유는 이 함수가 provider 를 고르지 않기 때문이다.
    어떤 구현을 쓸지는 호출부가 정한다.
    """
    config = cfg if cfg is not None else get_default_config()
    raw_text = resolver.complete(render_system_prompt(config), render_user_prompt(config, query))
    outcome = validate(parse_raw(raw_text), query)
    return ResolutionResult(
        resolution=outcome.resolution,
        findings=outcome.findings,
        resolution_schema_version=SCHEMA_VERSION,
        prompt_version=config.prompt_version,
        model_version=resolver.version,
    )
