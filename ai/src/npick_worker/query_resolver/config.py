"""프롬프트와 호출 파라미터, 그리고 그 버전(FRD F-05, FRD §11 실측 후 확정).

`scene_detection/config.py` 와 같은 구조다. 값은 전부
`config/query_resolver.v*.toml` 에 있고 그 해시가 `prompt_version` 이 된다.

`prompt_version` 과 `resolution_schema_version` 은 **다른 값**이다.
모듈 메타데이터로 따로 제공한다(§7.2 기록 지원). 프롬프트와 schema 를
고치는 일이 서로 다른 속도로 일어나기 때문이다. schema 버전은 `schema.py` 가 갖는다.
"""

import hashlib
import json
import re
import tomllib
from functools import lru_cache
from pathlib import Path
from typing import Any, Final

from pydantic import BaseModel, ConfigDict, Field

#: 패키지에 동봉된 기본 프롬프트. 휠에 포함되도록 src/npick_worker/config/ 아래 둔다.
DEFAULT_CONFIG_PATH: Final[Path] = (
    Path(__file__).resolve().parent.parent / "config" / "query_resolver.v1.toml"
)

#: prompt_version 뒤에 붙는 해시 길이. scene_detection 과 맞춘다.
_HASH_LENGTH: Final[int] = 8

_VERSIONED_CONFIG_NAME: Final[re.Pattern[str]] = re.compile(
    r"query_resolver\.v(?P<version>\d+)\.toml"
)


class _Frozen(BaseModel):
    # extra="forbid": toml 키 오타가 조용히 무시되면 prompt_version 은 바뀌는데
    # 동작은 그대로인 상황이 된다. 재현성 기록이 거짓이 되는 경로다.
    model_config = ConfigDict(frozen=True, extra="forbid")


class CallParams(_Frozen):
    """LLM 호출 파라미터. 전부 실측 후 확정 잠정값이다."""

    temperature: float = Field(ge=0.0, le=2.0)
    max_output_tokens: int = Field(gt=0)
    #: FRD §6.2 — retry 는 하지 않는다. 이 값이 검색 p95 예산 안에 들어야 한다.
    timeout_seconds: float = Field(gt=0)


class QueryResolverConfig(_Frozen):
    """toml 과 1:1 대응한다. 필드를 늘리면 prompt_version 이 바뀐다."""

    schema_: str = Field(alias="schema")
    system_prompt: str = Field(min_length=1)
    user_prompt: str = Field(min_length=1)
    call: CallParams

    @property
    def prompt_version(self) -> str:
        """`<schema>:<해시8>`.

        프롬프트 문구가 한 글자만 바뀌어도 해석 결과가 달라질 수 있으므로 해시로
        만든다. 호환성 판정이 필요한 `resolution_schema_version` 과 달리 여기서는
        "같은 문구인가" 만 알면 된다.
        """
        payload = self.model_dump(by_alias=True, mode="json")
        # sort_keys + 고정 separators: 같은 값이면 항상 같은 바이트열이어야 한다.
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        return f"{self.schema_}:{digest[:_HASH_LENGTH]}"


def load_config(path: Path | None = None) -> QueryResolverConfig:
    """toml 을 읽어 설정을 만든다. `path` 를 주면 프롬프트 실험에 쓸 수 있다."""
    target = path if path is not None else DEFAULT_CONFIG_PATH
    raw: dict[str, Any] = tomllib.loads(target.read_text(encoding="utf-8"))
    config = QueryResolverConfig.model_validate(raw)
    match = _VERSIONED_CONFIG_NAME.fullmatch(target.name)
    if match is not None:
        expected_schema = f"query-resolver-prompt/v{match.group('version')}"
        if config.schema_ != expected_schema:
            msg = (
                f"설정 파일 버전과 schema가 일치하지 않는다: "
                f"{target.name}에는 schema = {expected_schema!r}가 필요하다"
            )
            raise ValueError(msg)
    return config


@lru_cache(maxsize=1)
def get_default_config() -> QueryResolverConfig:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다."""
    return load_config()
