"""text embedding 설정과 그 버전.

`ocr/config.py` 와 같은 규약이다. 임계값을 코드에 두지 않는다. 값은 전부
`config/text_embedding.v*.toml` 에 있고 그 해시가 `version_id` 가 된다.

**모델 이름은 여기 없다.** 가중치 식별자는 `settings.py` 의 `NPICK_AI_EMBEDDING_MODEL`
이 정한다. `vlm_model` 과 같은 판단이다 — 선정이 아직 동결 전이고(S15P21A501-175 는
잠정 선정) 모델만 바꾸려고 설정 파일을 고치면 `config_version` 이 함께 움직여
"설정이 바뀌었나" 와 "모델이 바뀌었나" 를 나중에 구분할 수 없다. 두 값은 `jobs/versions.py`
의 `configVersion` 과 `modelVersion` 으로 따로 기록된다.
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
    Path(__file__).resolve().parent.parent / "config" / "text_embedding.v1.toml"
)

_VERSIONED_CONFIG_NAME: Final[re.Pattern[str]] = re.compile(
    r"text_embedding\.v(?P<version>\d+)\.toml"
)


class TextEmbeddingConfig(BaseModel):
    """toml 파일과 1:1 대응한다. 필드를 늘리면 version_id 가 바뀐다."""

    # extra="forbid": toml 키 오타가 조용히 무시되면 version_id 는 바뀌는데 동작은
    # 그대로인 상황이 된다. 재현성 기록이 거짓이 되는 경로다.
    model_config = ConfigDict(frozen=True, extra="forbid")

    schema_: str = Field(alias="schema")

    #: `scene.embedding vector(1024)` 와 같아야 한다. 어긋나면 BE 의 INSERT 가 실패한다.
    dimension: int = Field(gt=0)
    normalize: bool
    #: 문서측 접두. 모델 카드가 요구할 때만 값이 있다.
    document_prefix: str = ""
    section_separator: str = Field(min_length=1)
    dialogue_separator: str = Field(min_length=1)
    #: 어댑터가 한 번에 모델에 넣는 문장 수. 결과를 바꾸지 않는 실행 설정이다.
    batch_size: int = Field(gt=0)

    @property
    def version_id(self) -> str:
        """`<schema>:<해시8>`.

        이 값은 text_embedding **단계의 몫**이다. 단계 하나의 재현 식별자를
        `stageVersion` 으로 묶는 일은 `npick_worker.jobs.versions` 가,
        파이프라인 전체의 `pipeline_run.pipeline_version` 은 BE 가 한다
        (`docs/contracts/job-api.md`).
        """
        return version_id(self.schema_, self.model_dump(by_alias=True, mode="json"))


def load_config(path: Path | None = None) -> TextEmbeddingConfig:
    """toml 을 읽어 설정을 만든다. `path` 를 주면 차원·접두 실험에 쓸 수 있다."""
    target = path if path is not None else DEFAULT_CONFIG_PATH
    raw: dict[str, Any] = tomllib.loads(target.read_text(encoding="utf-8"))
    config = TextEmbeddingConfig.model_validate(raw)
    match = _VERSIONED_CONFIG_NAME.fullmatch(target.name)
    if match is not None:
        expected_schema = f"text-embedding/v{match.group('version')}"
        if config.schema_ != expected_schema:
            msg = (
                f"설정 파일 버전과 schema가 일치하지 않는다: "
                f"{target.name}에는 schema = {expected_schema!r}가 필요하다"
            )
            raise ValueError(msg)
    return config


@lru_cache(maxsize=1)
def get_default_config() -> TextEmbeddingConfig:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다."""
    return load_config()
