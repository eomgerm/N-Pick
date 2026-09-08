"""프롬프트 렌더링.

toml 의 템플릿에 값을 채운다. 채우는 값은 두 종류뿐이다.

- 날짜 필드명 — `schema.py` 의 `DateField` 에서 온다. 프롬프트와 schema 가 서로 다른
  이름을 말하면 LLM 출력이 항상 schema 검증에서 떨어진다. 정본을 한 곳에 두는 이유다.
- 사용자 질의 — 원문 그대로. 정규화한 질의를 넣지 않는다. `query_span` 은 원문 기준이고
  (`FRD F-05`) 정규화된 문자열을 넣으면 span 이 원문과 어긋난다.
"""

from typing import Final, get_args

from npick_worker.query_resolver.config import QueryResolverConfig
from npick_worker.query_resolver.schema import DateField

#: DateField Literal 에서 뽑는다. 하드코딩하면 schema.py 를 고쳤을 때 조용히 어긋난다.
_DATE_FIELDS: Final[tuple[str, ...]] = get_args(DateField)
BROADCAST_FIELD: Final[str] = _DATE_FIELDS[0]
FILMING_FIELD: Final[str] = _DATE_FIELDS[1]


def render_system_prompt(cfg: QueryResolverConfig) -> str:
    """날짜 필드명을 채운 system prompt."""
    return cfg.system_prompt.replace("{broadcast_field}", BROADCAST_FIELD).replace(
        "{filming_field}", FILMING_FIELD
    )


def render_user_prompt(cfg: QueryResolverConfig, query: str) -> str:
    """사용자 질의를 채운 user prompt.

    `query` 는 **원문**이다. canonical query 가 아니다.
    """
    return cfg.user_prompt.replace("{query}", query)
