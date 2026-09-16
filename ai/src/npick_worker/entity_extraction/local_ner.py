"""Local GPU NER adapter. Never downloads weights or sends input to a service."""

from importlib.metadata import version
from numbers import Real
from operator import index
from typing import Any, Final

from npick_worker.entity_extraction.config import Config
from npick_worker.entity_extraction.extractor import EntitySchemaInvalidError, EntitySpan
from npick_worker.versioning import version_id

#: Labels whose position in the BIO table is load-bearing. `num_labels` only proves the
#: table is the right size; a reordered table keeps that count and silently relabels every
#: entity. These anchors pin the published KPF order: both block starts, the last row of
#: each block, the row where the table stops being symmetric (`I-TM_CLIMATE` is absent, so
#: every `I-` row after it shifts by one) and the trailing `O`.
LABEL_ANCHORS: Final[tuple[tuple[int, str], ...]] = (
    (0, "B-AFA_ART_CRAFT"),
    (139, "B-TM_CLIMATE"),
    (149, "B-TR_SOCIAL_SCIENCE"),
    (150, "I-AFA_ART_CRAFT"),
    (289, "I-TM_COLOR"),
    (299, "O"),
)


def identity(config: Config) -> dict[str, str]:
    """The model-side reproducibility axes. Computable without loading the weights.

    Every value here is static: the config hash covers the TOML and the whole BIO table,
    the revision is pinned to a SHA, and the runtime versions come from package metadata.
    Nothing resolves at load time the way a moving `main` ref would, so declaring this
    before the first job reports the same value the job will report (contract §7).
    """
    return {
        "algorithmVersion": "entity-extraction/v1",
        "configVersion": config.version,
        "modelVersion": f"{config.model}@{config.revision}",
        "engineVersion": f"transformers{version('transformers')}+torch{version('torch')}",
    }


class EntityModelUnavailableError(RuntimeError):
    """MODEL_UNAVAILABLE: the weights or the GPU this stage needs are not on this worker.

    A separate class from `EntitySchemaInvalidError`, and for the same reason
    `vlm_metadata` splits the two — an unprovisioned worker is a transient fact about
    this pod, while malformed model output is a permanent fact about this input.
    """


class LocalNer:
    """Load an already provisioned, pinned Hugging Face snapshot on the worker GPU."""

    def __init__(self, config: Config) -> None:
        self.config = config
        self._pipeline: Any = None

    def load(self) -> None:
        import torch
        from transformers import AutoModelForTokenClassification, AutoTokenizer, pipeline

        if not torch.cuda.is_available():
            raise EntityModelUnavailableError("entity NER requires worker CUDA")
        try:
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
        except OSError as exc:
            # A snapshot that is not on this pod raises OSError from `local_files_only`,
            # and the job layer reads a bare OSError as `MEDIA_UNAVAILABLE` — a code whose
            # meaning is "the source video is missing". Translate at the loading boundary
            # so the record says which thing was missing (contract §9.2).
            raise EntityModelUnavailableError(
                f"entity NER weights are not provisioned: {exc}"
            ) from exc
        if model.config.num_labels != len(self.config.labels):
            raise EntityModelUnavailableError("BIO table does not match classifier")
        if any(self.config.labels[i] != label for i, label in LABEL_ANCHORS):
            raise EntityModelUnavailableError("BIO table rows are not in the published order")
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
        """What this instance is running. Reporting it before the load is a claim, not a fact."""
        if self._pipeline is None:
            raise RuntimeError("model has not been loaded")
        return identity(self.config)

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


#: One loaded model per process, keyed by the config hash. Not `lru_cache` on the config
#: itself: `Config` carries the two mapping dicts, so it is not hashable.
_SHARED: dict[str, LocalNer] = {}


def shared_ner(config: Config) -> LocalNer:
    """One model per worker process. **Everything that needs the model goes through here.**

    Loading is a few hundred MB onto the GPU. Without the cache every job pays it again,
    and the warm-up would only be pulling files into the page cache rather than handing the
    first job a loaded model (`ocr.shared_engine` says the same about its ONNX sessions).

    **Failures are not cached.** A worker that could not load stays out of `capabilities`
    (`jobs/registry._declared_version`) and retries on the next warm-up or restart, instead
    of remembering a half-built instance.
    """
    ner = _SHARED.get(config.version)
    if ner is None:
        ner = LocalNer(config)
        ner.load()
        _SHARED[config.version] = ner
    return ner


def is_loaded(config: Config) -> bool:
    """Has `shared_ner` loaded this configuration? **Asking never loads it.**

    `jobs/registry._declared_version` asks once per claim long-poll, synchronously, and a
    load here would stall the event loop while holding nothing useful. It is also the
    question that decides whether this worker advertises the stage at all: a pod with CUDA
    but no snapshot must not be assigned jobs it can only fail (`vlm_metadata`·`asr` guard
    on `is_loaded` for the same reason). Recovery is a re-warm or a restart.
    """
    return config.version in _SHARED
