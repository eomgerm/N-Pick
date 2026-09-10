"""OCR 설정과 그 버전.

`frame_extraction/config.py` 와 같은 규약이다. 임계값을 코드에 두지 않는다. 값은
전부 `config/ocr.v*.toml` 에 있고 그 해시가 `version_id` 가 된다. 티켓
`S15P21A501-94` 의 제약 — "확정된 품질 기준과 OCR 관련 설정은 코드에 하드코딩하지
않고 설정값으로 분리" — 이 지켜지는 자리다.
"""

import re
import tomllib
from functools import lru_cache
from pathlib import Path
from typing import Any, Final, Literal

from pydantic import BaseModel, ConfigDict, Field

from npick_worker.versioning import version_id

#: 패키지에 동봉된 기본 설정. 휠에 포함되도록 src/npick_worker/config/ 아래 둔다.
DEFAULT_CONFIG_PATH: Final[Path] = Path(__file__).resolve().parent.parent / "config" / "ocr.v1.toml"

_VERSIONED_CONFIG_NAME: Final[re.Pattern[str]] = re.compile(r"ocr\.v(?P<version>\d+)\.toml")

LimitType = Literal["max", "min"]
ModelSize = Literal["mobile", "server"]


class OcrConfig(BaseModel):
    """toml 파일과 1:1 대응한다. 필드를 늘리면 version_id 가 바뀐다."""

    # extra="forbid": toml 키 오타가 조용히 무시되면 "설정을 바꿨는데 결과가 같다" 는
    # 최악의 상황이 된다. version_id 는 바뀌는데 동작은 그대로이기 때문이다.
    model_config = ConfigDict(frozen=True, extra="forbid")

    schema_: str = Field(alias="schema")

    ocr_version: str = Field(min_length=1)
    det_lang: str = Field(min_length=1)
    det_model_type: ModelSize
    rec_lang: str = Field(min_length=1)
    rec_model_type: ModelSize

    det_limit_type: LimitType
    det_limit_side_len: int = Field(gt=0)
    det_thresh: float = Field(gt=0, le=1)
    det_box_thresh: float = Field(gt=0, le=1)
    det_unclip_ratio: float = Field(gt=0)
    det_use_dilation: bool

    #: 이 값 미만은 `unverified`. **버리는 기준이 아니다** — 관측은 전부 남긴다.
    #: `ocr_observation.confidence` 가 numeric(5,4) 라 상한이 1 이다.
    min_confidence: float = Field(ge=0, le=1)

    @property
    def version_id(self) -> str:
        """`<schema>:<해시8>`.

        이 값은 ocr **단계의 몫**이다. 단계 하나의 재현 식별자를 `stageVersion` 으로
        묶는 일은 `npick_worker.jobs.versions` 가, 파이프라인 전체의
        `pipeline_run.pipeline_version` 은 BE 가 한다(`docs/contracts/job-api.md`).
        """
        return version_id(self.schema_, self.model_dump(by_alias=True, mode="json"))


def load_config(path: Path | None = None) -> OcrConfig:
    """toml 을 읽어 설정을 만든다. `path` 를 주면 임계값 실험에 쓸 수 있다."""
    target = path if path is not None else DEFAULT_CONFIG_PATH
    raw: dict[str, Any] = tomllib.loads(target.read_text(encoding="utf-8"))
    config = OcrConfig.model_validate(raw)
    match = _VERSIONED_CONFIG_NAME.fullmatch(target.name)
    if match is not None:
        expected_schema = f"ocr/v{match.group('version')}"
        if config.schema_ != expected_schema:
            msg = (
                f"설정 파일 버전과 schema가 일치하지 않는다: "
                f"{target.name}에는 schema = {expected_schema!r}가 필요하다"
            )
            raise ValueError(msg)
    return config


@lru_cache(maxsize=1)
def get_default_config() -> OcrConfig:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다."""
    return load_config()
