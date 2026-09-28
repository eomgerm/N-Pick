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

from pydantic import BaseModel, ConfigDict, Field, model_validator

#: 패키지에 동봉된 기본 설정.
DEFAULT_CONFIG_PATH: Final[Path] = (
    Path(__file__).resolve().parent.parent / "config" / "query_normalization.v2.toml"
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
    #: 사용자 사전 항목에 주는 가산점. 0 이면 Kiwi 가 문맥에 따라 여전히 쪼갠다 —
    #: '보건복지부 기자회견' 이 '보건'+'복지'+'부' 로 갈렸다(실측). 3.0 에서 붙는다.
    user_word_score: float = 3.0
    aliases: dict[str, str] = Field(default_factory=dict)
    #: 질의 BM25 토큰에만 더하는 같은 뜻 토큰 묶음 (S15P21A501-320). 항목은 불용어와 같은
    #: `형태/품사태그` 다. 지문(`normalized_query`)과 색인은 이 값을 보지 않는다.
    search_token_synonyms: tuple[tuple[str, ...], ...] = ()

    @property
    def stopword_pairs(self) -> frozenset[tuple[str, str]]:
        """`("찾", "VV")` 형태의 집합. Kiwi 토큰과 직접 대조한다."""
        return frozenset(_pair(entry, "stopwords") for entry in self.stopwords)

    @property
    def synonym_groups(self) -> tuple[tuple[tuple[str, str], ...], ...]:
        """`search_token_synonyms` 를 `(형태, 품사)` 묶음으로."""
        return tuple(
            tuple(_pair(entry, "search_token_synonyms") for entry in group)
            for group in self.search_token_synonyms
        )

    @property
    def version_id(self) -> str:
        """`<schema>:<해시8>`. 동작을 바꾸는 변경에만 반응한다.

        리스트 항목은 정렬해서 해시한다. `keep_pos`·`stopwords`·`user_words` 는 소비
        시점에 전부 집합이라 순서가 동작을 안 바꾸는데, `json.dumps(sort_keys=True)` 는
        dict 키만 정렬하고 리스트 순서는 그대로 둔다. 가독성 때문에 불용어를 재정렬하면
        동작은 같은데 버전만 바뀌어 그때까지 쌓인 exclude_scene 이 전멸한다.
        """
        payload = self.model_dump(by_alias=True, mode="json")
        # 묶음이 없는 설정(v1)은 이 키가 생기기 전과 같은 해시여야 한다 — 옛 버전 문자열을
        # 그 설정 파일로 다시 재현할 수 있어야 저장 기록을 설명할 수 있다.
        if not payload["search_token_synonyms"]:
            del payload["search_token_synonyms"]
        for key, value in payload.items():
            if isinstance(value, list):
                # 묶음 목록(`search_token_synonyms`)은 묶음 안의 순서도 동작을 안 바꾼다.
                payload[key] = sorted(
                    sorted(item) if isinstance(item, list) else item for item in value
                )
        # sort_keys + 고정 separators: 같은 값이면 항상 같은 바이트열이어야 한다.
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        return f"{self.schema_}:{digest[:_HASH_LENGTH]}"

    @model_validator(mode="after")
    def _check_rules(self) -> "QueryNormalizationConfig":
        """설정 오류는 전부 로딩 시점에 터뜨린다.

        질의 시점에 터지면 `normalize()` 가 사용자 입력 오류에도 쓰는 `ValueError` 와
        섞여, 호출부가 설정 오타를 매 요청 400 으로 바꿔 버린다.
        """
        # 형식 검사를 여기서 한 번 돌려 둔다. property 로만 두면 첫 질의 때 터진다.
        _ = self.stopword_pairs

        # 치환은 토큰당 한 번이다. `A->B` 와 `B->C` 를 함께 두면 A 는 B 에서 멈추고
        # B 만 C 가 되어 같은 뜻의 두 표기가 다른 지문을 갖는다. 항등 별칭(`A->A`)은
        # 아무 일도 안 하므로 연쇄가 아니다.
        moving = {key: value for key, value in self.aliases.items() if key != value}
        chained = sorted(set(moving.values()) & set(moving))
        if chained:
            msg = f"별칭이 연쇄한다 — 다른 별칭의 키를 값으로 쓸 수 없다: {chained}"
            raise ValueError(msg)

        # 별칭이 불용어 필터보다 먼저라서, 불용어인 토큰을 별칭 키로 두면 치환된
        # 형태로 필터를 빠져나간다("태풍 영상" -> "비디오 태풍").
        stopword_forms = {form for form, _ in self.stopword_pairs}
        escaping = sorted(set(moving) & stopword_forms)
        if escaping:
            msg = f"불용어를 별칭 키로 쓸 수 없다 — 치환된 형태로 필터를 빠져나간다: {escaping}"
            raise ValueError(msg)

        # 한 토큰이 두 묶음에 있으면 어느 쪽으로 넓힐지가 묶음 순서에 달린다. 품사가 keep_pos
        # 밖이면 그 토큰은 질의에 나오지도 색인에 들어가지도 않아 묶음이 조용히 죽는다.
        seen: set[tuple[str, str]] = set()
        for group in self.synonym_groups:
            if len(set(group)) < 2:
                msg = f"search_token_synonyms 묶음은 서로 다른 토큰이 둘 이상이어야 한다: {group}"
                raise ValueError(msg)
            outside = [pair for pair in group if pair[1] not in self.keep_pos]
            if outside:
                msg = f"search_token_synonyms 의 품사가 keep_pos 밖이다: {outside}"
                raise ValueError(msg)
            repeated = sorted(seen & set(group))
            if repeated:
                msg = f"search_token_synonyms 의 토큰이 두 묶음에 있다: {repeated}"
                raise ValueError(msg)
            seen |= set(group)

        return self


def _pair(entry: str, key: str) -> tuple[str, str]:
    """`"찾/VV"` -> `("찾", "VV")`."""
    form, separator, tag = entry.partition(_STOPWORD_SEPARATOR)
    if not separator or not form or not tag:
        msg = f"{key} 항목은 '형태/품사태그' 형식이어야 한다: {entry!r}"
        raise ValueError(msg)
    return form, tag


def load_config(path: Path | None = None) -> QueryNormalizationConfig:
    """toml 을 읽어 설정을 만든다. `path` 를 주면 규칙 실험에 쓸 수 있다."""
    target = path if path is not None else DEFAULT_CONFIG_PATH
    raw: dict[str, Any] = tomllib.loads(target.read_text(encoding="utf-8"))
    return QueryNormalizationConfig.model_validate(raw)


@lru_cache(maxsize=1)
def get_default_config() -> QueryNormalizationConfig:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다."""
    return load_config()
