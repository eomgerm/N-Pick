"""자체 호스팅 VLM 어댑터 (transformers). **정본 경로다.**

`02-container.md` 요소 표가 VLM 을 워커의 자체 GPU 에 둔다. 이 파일이 그 경로이고,
외부 제공자는 `external_policy.py` 의 게이트를 통과할 때만 쓰는 대체 경로다.

**모델 이름이 이 파일에 없다.** 설정(`NPICK_AI_VLM_MODEL`)에서 온다. 후보 비교가 이
티켓의 일이고 결과가 나오기 전에 코드가 하나를 고르면 그게 곧 근거 없는 동결이다
(FRD §11, `settings.ollama_model` 과 같은 판단). 비어 있으면 이 단계는
`MODEL_UNAVAILABLE`(일시)로 실패한다 — 구현이 없는 `NO_ADAPTER`(영구)와 다른 사실이다.

**임포트는 모델 없이도 성공한다.** `transformers`·`torch` 는 함수 안에서 끌어온다.
`ai/AGENTS.md` 의 "GPU 없이도 워커가 기동하는 성질을 깨지 않는다" 와 `ocr` 이 rapidocr 를
지연 임포트하는 것과 같은 이유다.

**프로세스가 모델 하나를 공유한다.** 잡마다 가중치를 다시 올리면 8B 급에서 잡당 수십 초가
그 잡의 처리 시간이 되고, VRAM 도 두 벌이 필요해진다(`ocr.shared_engine` 과 같은 판단).
"""

import logging
from collections.abc import Sequence
from functools import lru_cache
from pathlib import Path
from typing import TYPE_CHECKING, Any, Final

from npick_worker.media_errors import MediaUnreadableError
from npick_worker.settings import Settings, get_settings
from npick_worker.vlm_metadata.client import (
    LabeledImage,
    VlmCallError,
    VlmModelUnavailableError,
)
from npick_worker.vlm_metadata.config import CallParams

if TYPE_CHECKING:  # 런타임에 끌어오지 않는다. 위 docstring 의 지연 임포트와 같은 이유다.
    from npick_worker.vlm_metadata.client import VlmClient

logger = logging.getLogger(__name__)

#: 어댑터 이름. 재현 튜플의 `engine` 이 된다.
ENGINE_NAME: Final[str] = "transformers"

#: 리비전을 지정하지 않았을 때 기록하는 값. 빈 문자열로 남기지 않는다 — "지정하지 않았다"
#: 와 "기록을 빠뜨렸다" 는 다르고, 나중에 어느 쪽인지 알 수 없으면 재현이 불가능하다.
DEFAULT_REVISION: Final[str] = "main"


class TransformersVlmClient:
    """`VlmClient` 구현. 이미지 여러 장과 프롬프트로 텍스트 하나를 받는다.

    `AutoProcessor` + `AutoModelForImageTextToText` 의 chat template 경로를 쓴다. 특정
    모델 계열의 전용 API 를 부르지 않는 이유는 후보가 아직 열려 있기 때문이다 — 이 경로는
    Qwen-VL·Gemma·InternVL 계열이 공통으로 지원하는 표면이고, 후보가 확정되면 그 모델의
    권장 호출로 좁힐 수 있다. 좁히더라도 바뀌는 것은 이 파일 하나다.
    """

    def __init__(
        self,
        model: str,
        *,
        revision: str = DEFAULT_REVISION,
        model_dir: Path | None = None,
        device: str | None = None,
    ) -> None:
        if not model:
            msg = "VLM 모델이 설정되지 않았다 (NPICK_AI_VLM_MODEL)"
            raise VlmModelUnavailableError(msg)
        self._model_id = model
        self._revision = revision or DEFAULT_REVISION
        self._model_dir = model_dir
        self._device = device
        self._loaded: tuple[Any, Any] | None = None

    @property
    def name(self) -> str:
        return ENGINE_NAME

    @property
    def version(self) -> str:
        """어댑터와 런타임의 버전. 가중치는 `model_version` 이 따로 말한다.

        torch 를 함께 적는 이유는 그것이 결과를 바꿀 수 있기 때문이다 — 커널·dtype 구현이
        바뀌면 같은 가중치에서 다른 토큰이 나올 수 있다(`ocr` 이 onnxruntime 버전을 함께
        싣는 것과 같은 이유).
        """
        from importlib.metadata import version

        try:
            torch_version = version("torch")
        except Exception:  # 버전 조회 실패가 호출을 막을 이유는 없다
            torch_version = "unknown"
        return f"transformers{version('transformers')}+torch{torch_version}"

    @property
    def model_version(self) -> str:
        """`<모델>@<리비전>`. 이름만으로는 부족하다 — 같은 이름의 가중치가 갱신된다."""
        return f"{self._model_id}@{self._revision}"

    def describe(
        self,
        images: Sequence[LabeledImage],
        system_prompt: str,
        user_prompt: str,
        params: CallParams,
    ) -> str:
        """모델이 낸 텍스트를 그대로 돌려준다. 파싱하지 않는다(`client.py`)."""
        if not images:
            msg = "넣을 이미지가 없다"
            raise VlmCallError(msg)
        processor, model = self._ensure_loaded()
        messages = _build_messages(images, system_prompt, user_prompt)
        try:
            return _generate(processor, model, messages, images, params)
        except (MemoryError, KeyboardInterrupt):
            # OOM 은 **그대로 올려보낸다.** `jobs/errors.classify` 가 이것을
            # `OUT_OF_MEMORY`(일시, 다른 파드에서 성공할 수 있다)로 번역한다. 여기서
            # VlmCallError 로 싸면 그 구분이 사라지고 전부 STAGE_FAILED 가 된다.
            raise
        except RuntimeError as exc:
            if "out of memory" in str(exc).lower():
                raise  # 같은 이유. classify 가 문자열로 OOM 을 알아본다.
            msg = f"VLM 호출이 실패했다: {exc}"
            raise VlmCallError(msg) from exc

    def warm_up(self) -> str:
        """첫 잡 전에 가중치를 올린다. 실패는 기동 시점에 드러난다."""
        self._ensure_loaded()
        return f"model={self.model_version} engine={self.name} {self.version}"

    def _ensure_loaded(self) -> tuple[Any, Any]:
        if self._loaded is None:
            self._loaded = _load(
                self._model_id,
                self._revision,
                str(self._model_dir) if self._model_dir is not None else None,
                self._device,
            )
        return self._loaded


def shared_client(settings: Settings | None = None) -> "VlmClient":
    """프로세스가 공유하는 클라이언트. 설정이 같으면 같은 인스턴스다."""
    config = settings if settings is not None else get_settings()
    return _shared_client(
        config.vlm_model,
        config.vlm_model_revision or DEFAULT_REVISION,
        config.vlm_model_dir,
        config.device,
    )


@lru_cache(maxsize=1)
def _shared_client(model: str, revision: str, model_dir: Path | None, device: str) -> "VlmClient":
    """`lru_cache` 로 인스턴스를 공유한다.

    `maxsize=1` 인 이유는 VRAM 이다. 설정이 바뀌면 새 인스턴스를 만들고 옛 것을 버리는데,
    둘을 동시에 들고 있으면 8GB 급 환경에서 그것만으로 OOM 이 된다.
    """
    from npick_worker.device import detect_device

    resolved = detect_device(device)
    return TransformersVlmClient(
        model,
        revision=revision,
        model_dir=model_dir,
        device=resolved.resolved,
    )


def _load(
    model_id: str, revision: str, cache_dir: str | None, device: str | None
) -> tuple[Any, Any]:
    """가중치를 올린다. 실패는 전부 `VlmModelUnavailableError`(일시)다.

    영구로 보고하지 않는 이유는 이 실패의 원인이 대개 환경이기 때문이다 — 캐시 볼륨이 안
    붙었거나 내려받기가 끊겼거나 VRAM 이 부족하다. 다른 파드나 다음 시도에서 성공할 수
    있다(계약 §9.2). `ocr` 이 같은 판단을 한다.
    """
    try:
        from transformers import AutoModelForImageTextToText, AutoProcessor
    except ImportError as exc:
        msg = "gpu 그룹이 설치되지 않았다: uv sync --group gpu"
        raise VlmModelUnavailableError(msg) from exc

    kwargs: dict[str, Any] = {"revision": revision}
    if cache_dir is not None:
        kwargs["cache_dir"] = cache_dir
    try:
        processor = AutoProcessor.from_pretrained(model_id, **kwargs)
        model = AutoModelForImageTextToText.from_pretrained(
            model_id,
            dtype="auto",
            # 단일 GPU 를 전제한다(`settings.job_concurrency` 주석). device_map 을 쓰지
            # 않는 이유는 accelerate 의 분할 배치가 8GB 급에서 CPU 오프로딩으로 조용히
            # 넘어가고, 그러면 추론 시간이 수십 배가 되는데 실패로는 보이지 않기 때문이다.
            **kwargs,
        )
    except Exception as exc:  # provider 예외를 어댑터 안에 가둔다
        msg = f"VLM 가중치를 준비하지 못했다: {model_id}@{revision} ({type(exc).__name__})"
        raise VlmModelUnavailableError(msg) from exc

    if device is not None and device != "cpu":
        try:
            model = model.to(device)
        except Exception as exc:  # VRAM 부족도 여기로 온다
            msg = f"VLM 가중치를 {device} 로 올리지 못했다: {type(exc).__name__}"
            raise VlmModelUnavailableError(msg) from exc
    model.eval()
    logger.info("VLM 가중치 준비 완료: %s@%s device=%s", model_id, revision, device)
    return processor, model


def _build_messages(
    images: Sequence[LabeledImage], system_prompt: str, user_prompt: str
) -> list[dict[str, Any]]:
    """chat template 이 받는 모양으로 만든다.

    **라벨을 이미지 바로 앞에 텍스트로 끼운다.** 이미지만 여러 장 넣으면 모델이 "몇 번째
    그림" 을 우리와 같은 이름으로 부를 방법이 없고, 그러면 근거 라벨 전체가 뜻을 잃는다
    (`client.LabeledImage` 주석). 순서만으로 맞추려는 시도는 모델이 순서를 바꿔 말하는
    순간 조용히 어긋난다.
    """
    content: list[dict[str, Any]] = []
    for image in images:
        content.append({"type": "text", "text": f"{image.label}:"})
        # 페이로드 없는 자리 표시자다. 실제 픽셀은 `_generate` 가 `images=` 로 따로
        # 넘긴다 — chat template 이 `url`·`path` 를 직접 읽는 경로는 런타임 버전마다
        # 의미가 갈리고, 그 차이가 "이미지를 못 본 채로 그럴듯한 답" 으로 나타난다.
        content.append({"type": "image"})
    content.append({"type": "text", "text": user_prompt})
    return [
        {"role": "system", "content": [{"type": "text", "text": system_prompt}]},
        {"role": "user", "content": content},
    ]


def _generate(
    processor: Any,
    model: Any,
    messages: list[dict[str, Any]],
    images: Sequence[LabeledImage],
    params: CallParams,
) -> str:
    """한 번 부른다. 재시도는 하지 않는다 — 그 판단은 BE 의 것이다(계약 §9.2).

    두 단계로 나눈 이유는 `_build_messages` 의 주석과 같다. 템플릿으로 **문자열**을 만들고
    픽셀은 `images=` 로 넘긴다. 이 경로가 Qwen-VL·Gemma·LLaVA 계열 문서가 공통으로 쓰는
    표면이라 후보가 바뀌어도 그대로 간다.
    """
    import torch
    from PIL import Image

    text = processor.apply_chat_template(messages, add_generation_prompt=True, tokenize=False)
    # 라벨 순서 그대로 연다. 이 순서가 `{"type": "image"}` 자리 표시자의 순서와 같아야
    # `kf_2` 가 실제로 두 번째 그림을 가리킨다.
    try:
        loaded = [Image.open(image.path).convert("RGB") for image in images]
    except OSError as exc:
        # 깨진 JPEG 은 **영구**다. 같은 파일을 다시 열어도 안 열린다. 그대로 올려보내면
        # `OSError` 분기가 `MEDIA_UNAVAILABLE`(일시)로 분류해 재시도가 같은 자리에서
        # 죽는다(`jobs/errors.classify`). `ocr` 이 같은 사실을 `UNSUPPORTED_MEDIA` 로
        # 번역하는 것과 맞춘다.
        msg = f"keyframe 이미지를 열 수 없다: {exc}"
        raise MediaUnreadableError(msg) from exc
    inputs = processor(text=text, images=loaded, return_tensors="pt").to(model.device)

    # temperature 0 은 샘플링을 끄는 것으로 구현한다. `do_sample=True` 에 temperature=0 을
    # 주면 라이브러리마다 0 으로 나누거나 조용히 1.0 으로 바꾼다 — 둘 다 재현성을 깬다.
    sampling: dict[str, Any] = (
        {"do_sample": False}
        if params.temperature == 0.0
        else {"do_sample": True, "temperature": params.temperature}
    )
    with torch.inference_mode():
        generated = model.generate(
            **inputs,
            max_new_tokens=params.max_output_tokens,
            **sampling,
        )
    # 프롬프트 토큰을 잘라낸다. 이걸 빼면 출력에 system prompt 가 그대로 섞여 나오고
    # JSON 파싱이 프롬프트 문장에서 실패한다.
    prompt_length = int(inputs["input_ids"].shape[1])
    completion = generated[0][prompt_length:]
    return str(processor.decode(completion, skip_special_tokens=True))
