"""단계 설정. 임계값을 코드에 숨기지 않고 설정 해시를 버전으로 노출한다(`ai/AGENTS.md`).

배정이 실어 보내는 `inputs.config` 와 다른 개념이다 — 계약 §4.5 대로 이 단계에는
빈 설정만 오고, 비어 있지 않으면 러너가 `StageConfigUnsupportedError` 로 거절한다.
"""

import tomllib
from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from npick_worker.versioning import version_id

DEFAULT_CONFIG = Path(__file__).resolve().parent.parent / "config" / "transcript_selection.v1.toml"


class TranscriptSelectionConfig(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")
    schema_: Literal["transcript-selection/v1"] = Field(alias="schema")
    #: 0 은 필터 없음. 상한을 두지 않는 대신 클립 길이와의 관계는 호출부가 본다.
    min_uncovered_ms: int = Field(ge=0)

    @property
    def version_id(self) -> str:
        return version_id(self.schema_, self.model_dump(by_alias=True, mode="json"))


def load_config(path: Path = DEFAULT_CONFIG) -> TranscriptSelectionConfig:
    return TranscriptSelectionConfig.model_validate(tomllib.loads(path.read_text(encoding="utf-8")))


@lru_cache(maxsize=1)
def get_default_config() -> TranscriptSelectionConfig:
    return load_config()
