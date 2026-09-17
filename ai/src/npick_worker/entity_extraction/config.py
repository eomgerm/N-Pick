"""Pinned model, BIO table and mapping form one reproducible configuration."""

import json
import tomllib
from functools import cached_property, lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field

from npick_worker.entity_extraction.schema import EntityType, StrictModel
from npick_worker.versioning import version_id

CONFIG_DIR = Path(__file__).resolve().parent.parent / "config"


class Config(StrictModel):
    schema_: Literal["entity-extraction-config/v1"] = Field(alias="schema")
    model: str
    revision: str = Field(pattern=r"^[0-9a-f]{40}$")
    minimum_confidence: float = Field(ge=0, le=1, allow_inf_nan=False)
    stride: int = Field(ge=0)
    aggregation_strategy: Literal["simple"]
    mapping: dict[str, EntityType]
    prefix_mapping: dict[str, EntityType]
    labels: tuple[str, ...]

    @cached_property
    def version(self) -> str:
        """300행 BIO 표까지 덮는 해시. 설정이 불변이므로 한 번만 계산한다.

        `_declared_version` 은 long-poll 한 바퀴마다 이 값을 `is_loaded` 와
        `identity` 에서 각각 한 번씩 묻는다. 매번 표 전체를 다시 해싱할 이유가 없다.
        `model_dump()` 에는 실리지 않으므로 해시의 입력이 스스로를 물지 않는다.
        """
        return version_id("entity-extraction-config/v1", self.model_dump())

    @property
    def label_map(self) -> dict[str, EntityType | None]:
        result: dict[str, EntityType | None] = {}
        for bio in self.labels:
            if bio == "O":
                continue
            label = bio.removeprefix("B-").removeprefix("I-")
            result[label] = self.mapping.get(label)
            if label not in self.mapping:
                for prefix, tag_type in self.prefix_mapping.items():
                    if label.startswith(prefix):
                        result[label] = tag_type
                        break
        return result


def load_config(path: Path = CONFIG_DIR / "entity_extraction.v1.toml") -> Config:
    data = tomllib.loads(path.read_text(encoding="utf-8"))
    data["labels"] = tuple(json.loads((CONFIG_DIR / "entity_kpf_labels.v1.json").read_text()))
    return Config.model_validate(data)


@lru_cache(maxsize=1)
def get_default_config() -> Config:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다.

    claim 과 실행이 같은 설정을 봐야 한다. `_declared_version` 은 long-poll 마다 이것을
    읽고 `run` 은 잡마다 읽는데, 그 사이 파일이 바뀌면 선언한 `stageVersion` 과 결과가
    보고하는 값이 갈린다(계약 §7). 300행 BIO 표를 매번 다시 읽고 해싱하는 비용도 같이
    없앤다. 실측 하네스는 `load_config(path)` 로 다른 설정을 가리킬 수 있다.
    """
    return load_config()
