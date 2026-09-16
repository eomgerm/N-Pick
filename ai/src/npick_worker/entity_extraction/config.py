"""Pinned model, BIO table and mapping form one reproducible configuration."""

import json
import tomllib
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

    @property
    def version(self) -> str:
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
