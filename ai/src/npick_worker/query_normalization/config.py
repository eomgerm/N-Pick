"""질의 정규화 설정과 그 버전 (FR-QRY-004).

`scene_detection/config.py` 와 같은 구조다 — 값은 toml 에만 두고 그 해시를
version 으로 노출한다. 다른 점은 이 version 이 **저장된 기록의 조회 키** 라는
것이다. scene detection 의 version_id 는 "어떤 설정으로 나온 결과인가" 를
설명할 뿐이지만, normalization_version 은 바뀌는 순간 기존 장면 제외 규칙이
통째로 안 걸리게 된다.
"""

import hashlib
import json
import tomllib
from functools import lru_cache
from pathlib import Path
from typing import Any, Final

from pydantic import BaseModel, ConfigDict, Field

#: 패키지에 동봉된 기본 설정.
DEFAULT_CONFIG_PATH: Final[Path] = (
    Path(__file__).resolve().parent.parent / "config" / "query_normalization.v1.toml"
)

#: version_id 뒤에 붙는 해시 길이. scene detection 과 맞춘다.
_HASH_LENGTH: Final[int] = 8

#: `stopwords` 항목의 구분자. "찾/VV" → ("찾", "VV")
_STOPWORD_SEPARATOR: Final[str] = "/"


class QueryNormalizationConfig(BaseModel):
    # extra="forbid": toml 키 오타가 조용히 무시되면 version_id 는 바뀌는데
    # 동작은 그대로인 최악의 상황이 된다.
    model_config = ConfigDict(frozen=True, extra="forbid")

    schema_: str = Field(alias="schema")
    keep_pos: tuple[str, ...] = Field(min_length=1)
    sort_tokens: bool
    stopwords: tuple[str, ...]
    #: Kiwi 가 한 토큰으로 보게 강제할 단어. 토큰 경계는 문맥에 따라 달라진다 —
    #: '서울특별시' 는 홀로 두면 NNP 한 토큰이지만 '서울특별시 집중호우' 에서는
    #: '서울'+'특별시' 로 쪼개진다(실측). 그러면 별칭 키가 안 걸린다.
    user_words: tuple[str, ...] = ()
    aliases: dict[str, str] = Field(default_factory=dict)

    @property
    def stopword_pairs(self) -> frozenset[tuple[str, str]]:
        """`("찾", "VV")` 형태의 집합. Kiwi 토큰과 직접 대조한다."""
        pairs = []
        for entry in self.stopwords:
            form, separator, tag = entry.partition(_STOPWORD_SEPARATOR)
            if not separator or not form or not tag:
                msg = f"stopwords 항목은 '형태/품사태그' 형식이어야 한다: {entry!r}"
                raise ValueError(msg)
            pairs.append((form, tag))
        return frozenset(pairs)

    @property
    def version_id(self) -> str:
        """`<schema>:<해시8>`. 파일 내용이 1바이트라도 다르면 달라진다."""
        payload = self.model_dump(by_alias=True, mode="json")
        # sort_keys + 고정 separators: 같은 값이면 항상 같은 바이트열이어야 한다.
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        return f"{self.schema_}:{digest[:_HASH_LENGTH]}"


def load_config(path: Path | None = None) -> QueryNormalizationConfig:
    """toml 을 읽어 설정을 만든다. `path` 를 주면 규칙 실험에 쓸 수 있다."""
    target = path if path is not None else DEFAULT_CONFIG_PATH
    raw: dict[str, Any] = tomllib.loads(target.read_text(encoding="utf-8"))
    return QueryNormalizationConfig.model_validate(raw)


@lru_cache(maxsize=1)
def get_default_config() -> QueryNormalizationConfig:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다."""
    return load_config()
