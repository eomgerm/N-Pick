"""자체 호스팅 임베딩 어댑터 (sentence-transformers). **정본 경로다.**

`02-container.md` 요소 표가 임베딩을 워커 쪽에 둔다. S15P21A501-175 의 비교 하네스가
쓴 런타임과 같은 것을 쓴다 — 다른 런타임으로 재면 비교표의 수치와 운영에서 나오는
벡터가 같다는 보장이 없다.

**모델 이름이 이 파일에 없다.** 설정(`NPICK_AI_EMBEDDING_MODEL`)에서 온다 — 확정값은 그
기본값이고(S15P21A501-175), 어댑터는 무엇이 오든 같은 표면으로 받는다. 명시적으로 빈 값을
주면 `MODEL_UNAVAILABLE`(일시)로 실패한다 — 구현이 없는 `NO_ADAPTER`(영구)와 다른 사실이다.

**임포트는 모델 없이도 성공한다.** `sentence_transformers`·`torch` 는 함수 안에서
끌어온다. `ai/AGENTS.md` 의 "GPU 없이도 워커가 기동하는 성질을 깨지 않는다" 와 같은
이유다. 이 단계는 CPU 에서도 돌아간다 — S15P21A501-175 실측이 CPU 단건 p95 110~120ms 로
`p95 10초` 예산의 약 1.2% 다.

**프로세스가 모델 하나를 공유한다.** 잡마다 가중치를 다시 올리면 1.7GB 를 잡마다 읽는다
(`ocr.shared_engine`·`vlm.shared_client` 와 같은 판단).
"""

import logging
import re
from collections.abc import Sequence
from functools import lru_cache
from pathlib import Path
from typing import TYPE_CHECKING, Any, Final, cast

from npick_worker.settings import DeviceChoice, Settings, get_settings
from npick_worker.text_embedding.encoder import (
    EmbeddingCallError,
    EmbeddingModelUnavailableError,
)

if TYPE_CHECKING:  # 런타임에 끌어오지 않는다. 위 docstring 의 지연 임포트와 같은 이유다.
    from npick_worker.text_embedding.encoder import TextEncoder

logger = logging.getLogger(__name__)

#: 어댑터 이름. 재현 튜플의 `adapter` 가 된다.
ADAPTER_NAME: Final[str] = "sentence-transformers"

#: 리비전을 지정하지 않았을 때 기록하는 값. 빈 문자열로 남기지 않는다 — "지정하지 않았다"
#: 와 "기록을 빠뜨렸다" 는 다르다(`vlm_metadata` 의 같은 판단).
DEFAULT_REVISION: Final[str] = "main"

#: 고정된 가중치를 가리키는 리비전의 모양. hub 의 commit SHA 다.
_PINNED_REVISION: Final[re.Pattern[str]] = re.compile(r"[0-9a-f]{40}")

#: CUDA OOM 을 알아보는 표식. `jobs/errors.py` 의 `_CUDA_OOM_MARKER` 와 같은 문자열이어야
#: 한다 — 여기서 통과시킨 예외를 그쪽 `classify` 가 받아 `OUT_OF_MEMORY` 로 옮긴다.
_CUDA_OOM_MARKER: Final[str] = "out of memory"


class SentenceTransformerEncoder:
    """`TextEncoder` 구현. 문장 목록을 벡터 목록으로 바꾼다.

    `SentenceTransformer.encode` 만 쓴다. 특정 모델 계열의 전용 API 를 부르지 않는
    이유는 후보가 아직 열려 있기 때문이다 — arctic-ko·PIXIE·KURE 가 전부 이 표면을
    공유하고(S15P21A501-175 하네스가 그렇게 쟀다), 후보가 확정돼도 바뀌는 것은 이
    파일 하나다.
    """

    def __init__(
        self,
        model_id: str,
        *,
        batch_size: int,
        revision: str = DEFAULT_REVISION,
        model_dir: Path | None = None,
        device: str | None = None,
    ) -> None:
        if not model_id:
            msg = (
                "임베딩 모델이 빈 값이다. NPICK_AI_EMBEDDING_MODEL 을 비우지 않는다 "
                # 값을 복제하지 않는다. 한쪽만 바뀌면 오류 메시지가 조용히 거짓말한다.
                f"(확정값: {Settings.model_fields['embedding_model'].default})"
            )
            raise EmbeddingModelUnavailableError(msg)
        self._model_id = model_id
        self._batch_size = batch_size
        self._revision = revision or DEFAULT_REVISION
        self._model_dir = model_dir
        self._device = device
        self._model: Any | None = None
        self._resolved_revision: str | None = None
        self._dimension: int | None = None

    @property
    def name(self) -> str:
        return ADAPTER_NAME

    @property
    def version(self) -> str:
        """어댑터와 런타임의 버전. 가중치는 `model_version` 이 따로 말한다."""
        return f"{ADAPTER_NAME}/{_library_version()}"

    @property
    def model_version(self) -> str:
        """가중치의 식별자. 로딩 전에는 선언한 리비전, 로딩 후에는 확정된 SHA 다."""
        return f"{self._model_id}@{self._resolved_revision or self._revision}"

    def encode(self, texts: Sequence[str]) -> tuple[tuple[float, ...], ...]:
        """문장 목록을 벡터 목록으로 바꾼다.

        **정규화하지 않는다.** `normalize_embeddings` 를 켜지 않는 것은 L2 정규화를
        `embedder.py` 한 곳에서 하기로 했기 때문이다(`encoder.py` 의 Protocol 주석).
        어댑터마다 켜고 끄면 같은 설정에서 다른 크기의 벡터가 나온다.

        `.tolist()` 로 numpy 를 벗겨 내보낸다 — 배열 타입이 경계 밖으로 새면 호출부가
        어댑터의 런타임을 알게 된다.
        """
        if not texts:
            return ()
        model = self._ensure_loaded()
        try:
            vectors = model.encode(
                list(texts),
                batch_size=self._batch_size,
                normalize_embeddings=False,
                show_progress_bar=False,
            )
            return tuple(tuple(float(value) for value in row) for row in vectors.tolist())
        except MemoryError:
            # **감싸지 않고 그대로 올린다.** `ocr/rapidocr_backend.py` 와 같은 판단이다.
            # `EmbeddingCallError` 로 감싸면 `jobs/errors.classify` 가 `STAGE_FAILED` 로
            # 떨어뜨리는데, 계약 §9.2 는 `OUT_OF_MEMORY`(일시)를 따로 두고 있다. 그대로
            # 올리면 `classify` 가 MemoryError → OUT_OF_MEMORY 로 옮긴다 — 더 큰 파드나
            # 작은 배치에서는 성공했을 잡이다.
            raise
        except RuntimeError as exc:
            # CUDA OOM 은 `RuntimeError("CUDA out of memory. ...")` 로 온다. `classify` 가
            # 그 문자열을 보고 OUT_OF_MEMORY 로 옮기므로 여기서도 그대로 올린다.
            # 큰 `batch_size` 와 긴 대사가 겹치면 실제로 VRAM 을 터뜨릴 수 있는 조합이다.
            if _CUDA_OOM_MARKER in str(exc).lower():
                raise
            msg = f"임베딩 호출이 실패했다: {type(exc).__name__}: {exc}"
            raise EmbeddingCallError(msg) from exc
        except Exception as exc:  # 런타임 오류. 다음 시도에서 성공할 수 있다
            msg = f"임베딩 호출이 실패했다: {type(exc).__name__}: {exc}"
            raise EmbeddingCallError(msg) from exc

    def warm_up(self) -> str:
        """첫 잡 전에 가중치를 올린다. 배선 티켓의 `_warm_*` 자리가 이것을 부른다."""
        self._ensure_loaded()
        return f"model={self.model_version} engine={self.name} {self.version} dim={self._dimension}"

    def _ensure_loaded(self) -> Any:
        if self._model is None:
            model, resolved, dimension = _load(
                self._model_id,
                self._revision,
                self._model_dir,
                self._device,
            )
            # 순서가 중요하다. `_model` 을 먼저 넣으면 다른 스레드가 `model_version` 을
            # 읽을 때 확정 전 ref 를 볼 수 있다(`vlm_metadata` 의 같은 판단).
            self._resolved_revision = resolved
            self._dimension = dimension
            self._model = model
        return self._model


@lru_cache(maxsize=1)
def _library_version() -> str:
    from importlib.metadata import PackageNotFoundError, version

    try:
        return version("sentence-transformers")
    except PackageNotFoundError:  # pragma: no cover - gpu 그룹 미설치
        return "unknown"


def _load(
    model_id: str,
    revision: str,
    model_dir: Path | None,
    device: str | None,
) -> tuple[Any, str, int]:
    """가중치를 올리고 `(모델, 확정 리비전, 차원)` 을 돌려준다."""
    from sentence_transformers import SentenceTransformer

    kwargs: dict[str, Any] = {"revision": revision}
    if model_dir is not None:
        kwargs["cache_folder"] = str(model_dir)
    if device is not None:
        kwargs["device"] = device
    try:
        model = SentenceTransformer(model_id, **kwargs)
    except MemoryError:
        # **감싸지 않고 그대로 올린다.** `encode` 와 같은 정책이다 — 생성자가 가중치를
        # GPU 로 올리므로 1.7GB 를 VRAM 에 넣는 자리가 바로 여기다. 감싸면 `classify` 가
        # `STAGE_FAILED` 로 떨어뜨려 원인이 "가중치를 못 받았다" 로 기록되는데, 실제로는
        # 메모리가 모자랐던 것이라 더 큰 파드에서는 성공한다(계약 §9.2 `OUT_OF_MEMORY`).
        raise
    except RuntimeError as exc:
        if _CUDA_OOM_MARKER in str(exc).lower():
            raise
        msg = f"임베딩 가중치를 준비하지 못했다: {model_id}@{revision} ({type(exc).__name__})"
        raise EmbeddingModelUnavailableError(msg) from exc
    except Exception as exc:
        # 가중치를 못 받은 것은 이 클립의 문제가 아니다. 다른 파드나 다음 시도에서
        # 성공할 수 있다 — 계약 §9.2 의 `MODEL_UNAVAILABLE`(일시)이다.
        msg = f"임베딩 가중치를 준비하지 못했다: {model_id}@{revision} ({type(exc).__name__})"
        raise EmbeddingModelUnavailableError(msg) from exc

    # ST 5.7 에서 `get_sentence_embedding_dimension` 이 이 이름으로 바뀌었다(FutureWarning).
    dimension = model.get_embedding_dimension() or 0
    if dimension <= 0:
        msg = f"모델이 임베딩 차원을 말하지 않는다: {model_id}@{revision}"
        raise EmbeddingModelUnavailableError(msg)

    resolved = _resolve_revision(model, revision)
    # `max_seq_length` 를 남긴다. ST 는 이 길이를 넘는 입력을 **조용히 자르고**, 그러면
    # `SceneEmbedding.source_text` 의 전문과 실제 임베딩된 것이 달라진다(FRD §7.2).
    # 건별 감지는 입력마다 토크나이즈해야 해서 비용이 붙는다 — 실측은 eval 하네스가 한다.
    logger.info(
        "임베딩 가중치 준비 완료: %s@%s dim=%d max_seq=%s device=%s",
        model_id,
        resolved,
        dimension,
        getattr(model, "max_seq_length", "unknown"),
        device,
    )
    return model, resolved, dimension


def _resolve_revision(model: Any, revision: str) -> str:
    """실제로 올라간 가중치의 commit SHA. 알아내지 못하면 선언한 값 그대로다.

    `main` 같은 움직이는 ref 로 받은 실행은 **무엇을 돌렸는지 나중에 말할 수 없다.**
    원격이 갱신되면 같은 `<모델>@main` 이 다른 가중치를 가리키는데 기록은 그대로다.
    임베딩에서는 이것이 특히 아프다 — 벡터는 사람이 읽고 이상하다고 알아챌 수 있는
    산출물이 아니라서, 가중치가 바뀐 것을 검색 품질이 떨어진 뒤에야 알게 된다.

    `vlm_metadata/transformers_backend.py` 의 같은 함수와 판단이 같다. 두 어댑터가
    서로 다른 런타임 객체를 다루므로(`SentenceTransformer` 대 `AutoModel...`) 공유
    모듈로 묶지 않는다 — `ai/AGENTS.md` 가 공유 모듈을 늘리지 말라고 한 자리다.
    """
    if _PINNED_REVISION.fullmatch(revision):
        return revision
    for module in getattr(model, "_modules", {}).values():
        commit = getattr(getattr(module, "config", None), "_commit_hash", None)
        if isinstance(commit, str) and _PINNED_REVISION.fullmatch(commit):
            return commit
    logger.warning(
        "임베딩 가중치 리비전을 SHA 로 확정하지 못했다: %s. 이 실행의 벡터는 나중에 "
        "같은 가중치로 재생성된다고 말할 수 없다 — 운영에는 SHA 를 고정해 쓴다",
        revision,
    )
    return revision


def shared_encoder(settings: Settings | None = None) -> "TextEncoder":
    """프로세스가 공유하는 인코더. 설정이 같으면 같은 인스턴스다."""
    resolved = settings if settings is not None else get_settings()
    return _shared_encoder(
        resolved.embedding_model,
        resolved.embedding_model_revision or DEFAULT_REVISION,
        resolved.embedding_model_dir,
        resolved.device,
        resolved.embedding_batch_size,
    )


@lru_cache(maxsize=1)
def _shared_encoder(
    model: str,
    revision: str,
    model_dir: Path | None,
    device_choice: str,
    batch_size: int,
) -> "TextEncoder":
    """**실패는 캐시되지 않는다**(`lru_cache` 의 동작).

    가중치를 준비하지 못한 워커는 다음 잡에서 다시 시도한다 — `MODEL_UNAVAILABLE` 이
    일시 오류인 것과 맞물린다. 생성 자체는 가볍고(가중치는 첫 `encode` 에서 올라간다)
    여기서 캐시하는 것은 인스턴스지 로딩 결과가 아니다.
    """
    from npick_worker.device import detect_device

    # DeviceChoice 를 그대로 넘기지 않는다. `auto` 는 sentence-transformers 가 모르는
    # 값이고, 실제 해석은 워커 전체가 한 곳(`device.py`)에서 한다.
    resolved_device = detect_device(cast(DeviceChoice, device_choice)).resolved
    return SentenceTransformerEncoder(
        model,
        batch_size=batch_size,
        revision=revision,
        model_dir=model_dir,
        device=resolved_device,
    )
