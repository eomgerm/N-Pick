"""Local GPU NER adapter. Never downloads weights or sends input to a service."""

from importlib.metadata import version
from numbers import Real
from operator import index
from typing import Any

from npick_worker.entity_extraction.config import Config
from npick_worker.entity_extraction.extractor import EntitySchemaInvalidError, EntitySpan
from npick_worker.versioning import version_id


class LocalNer:
    """Load an already provisioned, pinned Hugging Face snapshot on the worker GPU."""

    def __init__(self, config: Config) -> None:
        self.config = config
        self._pipeline: Any = None

    def load(self) -> None:
        import torch
        from transformers import AutoModelForTokenClassification, AutoTokenizer, pipeline

        if not torch.cuda.is_available():
            raise RuntimeError("MODEL_UNAVAILABLE: entity NER requires worker CUDA")
        tokenizer = AutoTokenizer.from_pretrained(
            self.config.model,
            revision=self.config.revision,
            local_files_only=True,
        )
        model = AutoModelForTokenClassification.from_pretrained(
            self.config.model,
            revision=self.config.revision,
            local_files_only=True,
        )
        if model.config.num_labels != len(self.config.labels):
            raise ValueError("MODEL_UNAVAILABLE: BIO table does not match classifier")
        model.config.id2label = dict(enumerate(self.config.labels))
        model.config.label2id = {label: i for i, label in enumerate(self.config.labels)}
        model.eval()
        self._pipeline = pipeline(
            "token-classification",
            model=model,
            tokenizer=tokenizer,
            aggregation_strategy=self.config.aggregation_strategy,
            device=0,
            stride=self.config.stride,
        )

    @property
    def identity(self) -> dict[str, str]:
        if self._pipeline is None:
            raise RuntimeError("model has not been loaded")
        return {
            "algorithmVersion": "entity-extraction/v1",
            "configVersion": self.config.version,
            "modelVersion": f"{self.config.model}@{self.config.revision}",
            "engineVersion": f"transformers{version('transformers')}+torch{version('torch')}",
        }

    @property
    def stage_version(self) -> str:
        return version_id("npick.stage.entity_extraction/v1", self.identity)

    def predict(self, text: str) -> tuple[EntitySpan, ...]:
        if self._pipeline is None:
            self.load()
        rows = self._pipeline(text)
        try:
            for row in rows:
                if (
                    isinstance(row["start"], bool)
                    or isinstance(row["end"], bool)
                    or isinstance(row["score"], bool)
                    or not isinstance(row["score"], Real)
                ):
                    raise ValueError("invalid offset or confidence type")
            return tuple(
                EntitySpan(
                    label=row["entity_group"],
                    start=index(row["start"]),
                    end=index(row["end"]),
                    confidence=float(row["score"]),
                )
                for row in rows
            )
        except (ValueError, TypeError, KeyError) as exc:
            raise EntitySchemaInvalidError("invalid NER output") from exc
