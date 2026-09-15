"""질의 임베딩 설정과 그 버전.

`query_normalization/config.py` 와 같은 규약이다. 값은 전부
`config/query_embedding.v*.toml` 에 있고 그 해시가 `version_id` 가 된다.

**모델 이름은 여기 없다.** 가중치 식별자는 `settings.py` 의 `NPICK_AI_EMBEDDING_MODEL`
이고 색인 측과 **같은 값 하나**를 쓴다. 질의 측이 따로 고를 수 있게 만들면 두 벡터가
다른 공간에 놓여 유사도가 무의미해진다 — 일감 제약이 막으려는 바로 그 상태다.
"""

import re
import tomllib
from functools import lru_cache
from pathlib import Path
from typing import Any, Final

from pydantic import BaseModel, ConfigDict, Field

from npick_worker.versioning import version_id

#: 패키지에 동봉된 기본 설정. 휠에 포함되도록 src/npick_worker/config/ 아래 둔다.
DEFAULT_CONFIG_PATH: Final[Path] = (
    Path(__file__).resolve().parent.parent / "config" / "query_embedding.v1.toml"
)

_VERSIONED_CONFIG_NAME: Final[re.Pattern[str]] = re.compile(
    r"query_embedding\.v(?P<version>\d+)\.toml"
)


class QueryEmbeddingConfig(BaseModel):
    """toml 파일과 1:1 대응한다. 필드를 늘리면 version_id 가 바뀐다."""

    # extra="forbid": toml 키 오타가 조용히 무시되면 version_id 는 바뀌는데 동작은
    # 그대로인 상황이 된다. 재현성 기록이 거짓이 되는 경로다.
    model_config = ConfigDict(frozen=True, extra="forbid")

    schema_: str = Field(alias="schema")

    #: `text_embedding.v1.toml` 의 같은 이름 값과 **같아야 한다**.
    dimension: int = Field(gt=0)
    #: 위와 같다. 한쪽만 끄면 코사인이 아니라 내적이 된다.
    normalize: bool
    #: 질의측 접두. arctic-ko 는 `query: ` 를 요구한다. 모델 카드가 정하는 값이라
    #: 코드가 아니라 설정에 둔다.
    query_prefix: str = ""

    @property
    def version_id(self) -> str:
        """`<schema>:<해시8>`.

        이 값은 **단계 버전이 아니다.** 질의 임베딩은 `stages.py` 에 없는 검색 시점
        모듈이라 `stageVersion` 에 들어가지 않는다. 호출부가 재현 기록에 쓸 수 있도록
        같은 형식으로만 노출한다(`normalization_version` 과 같은 자리).
        """
        return version_id(self.schema_, self.model_dump(by_alias=True, mode="json"))


def load_config(path: Path | None = None) -> QueryEmbeddingConfig:
    """toml 을 읽어 설정을 만든다. `path` 를 주면 접두 실험에 쓸 수 있다."""
    target = path if path is not None else DEFAULT_CONFIG_PATH
    raw: dict[str, Any] = tomllib.loads(target.read_text(encoding="utf-8"))
    config = QueryEmbeddingConfig.model_validate(raw)
    match = _VERSIONED_CONFIG_NAME.fullmatch(target.name)
    if match is not None:
        expected_schema = f"query-embedding/v{match.group('version')}"
        if config.schema_ != expected_schema:
            msg = (
                f"설정 파일 버전과 schema가 일치하지 않는다: "
                f"{target.name}에는 schema = {expected_schema!r}가 필요하다"
            )
            raise ValueError(msg)
    return config


@lru_cache(maxsize=1)
def get_default_config() -> QueryEmbeddingConfig:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다."""
    return load_config()
