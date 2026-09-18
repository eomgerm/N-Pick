"""`AsrEngine` 의 faster-whisper(CTranslate2) 구현.

**이 파일 밖으로 faster_whisper 타입이 나가지 않는다.** 나가면 엔진 교체가 단계
전체를 건드리는 일이 된다(`ai/AGENTS.md` 의 어댑터 경계).

왜 faster-whisper 인가는 `docs/asr.md` §2 가 답한다. 요약하면 저장소가 이미 그것을
전제하고 있고(`pyproject.toml` 의 `gpu` 그룹, `tests/test_smoke_models.py`), **VAD 가
라이브러리 안에 들어 있어** 티켓이 요구하는 "ASR 실행기 내부의 VAD·무음 처리" 를
별도 구성 없이 만족한다. 저장소에 다른 VAD 구성은 없다.

`gpu` 그룹은 선택 의존성이다. 이 모듈은 **함수 안에서만** faster_whisper 를 임포트해
GPU 없는 환경에서도 워커가 기동하는 성질을 지킨다(`ai/AGENTS.md`, `device.py` 의
torch 와 같은 규칙).
"""

import logging
import re
from functools import lru_cache
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any, Final

from npick_worker.asr.config import AsrConfig
from npick_worker.asr.engine import (
    AsrCallError,
    AsrModelUnavailableError,
    SpeechSegment,
    Transcription,
)
from npick_worker.device import detect_device
from npick_worker.media_errors import MediaUnreadableError
from npick_worker.settings import Settings, get_settings

logger = logging.getLogger(__name__)

ENGINE_NAME: Final[str] = "faster-whisper"

#: Whisper 계열이 받는 유일한 표본율. 이 값은 모델 구조에서 온 상수라 설정 키가 아니다.
SAMPLING_RATE: Final[int] = 16000

#: compute type 을 고르지 않았을 때 장치별로 쓰는 값. **품질 기준이 아니라 그 장치에서
#: 도는 값**이다 — CUDA 가 아닌 곳에서 float16 은 아예 실행되지 않는다. 실측으로 정할
#: 것은 "어느 쪽이 더 나은가" 이고 그 결정은 `NPICK_AI_ASR_COMPUTE_TYPE` 으로 들어온다.
_DEFAULT_COMPUTE_TYPE: Final[dict[str, str]] = {"cuda": "float16", "cpu": "int8"}

#: 가중치 리비전이 고정됐는가. `text_embedding` 의 `_PINNED_REVISION` 과 같은 판정이다.
_PINNED_REVISION: Final[re.Pattern[str]] = re.compile(r"[0-9a-f]{40}")


def is_pinned_revision(revision: str) -> bool:
    """40자리 커밋 SHA 인가.

    `large-v3-turbo` 같은 크기 이름은 faster-whisper 가 HF 저장소로 풀어 준다
    (`mobiuslabsgmbh/faster-whisper-large-v3-turbo`). 그 저장소가 갱신되면 같은 이름이
    다른 가중치를 가리키는데 `modelVersion` 은 그대로라, 다른 가중치로 만든 전사가 같은
    `stageVersion` 을 달고 정본에 들어간다. 그래서 움직이는 ref 로는 능력을 선언하지
    않는다 (S15P21A501-187).
    """
    return bool(_PINNED_REVISION.fullmatch(revision))


_CUDA_OOM_MARKER: Final[str] = "out of memory"


class AsrRuntimeMissingError(RuntimeError):
    """faster-whisper 가 설치되지 않았다. 이 이미지에 **구현이 없다**는 뜻이다.

    `AsrModelUnavailableError`(일시)와 갈라야 한다. 가중치는 다음 시도나 다른 파드에서
    준비될 수 있지만, 라이브러리가 없는 것은 이 이미지의 성질이라 재시도가 고칠 수
    없다 — 계약 §9.2 의 `NO_ADAPTER`(영구 → `skipped`)가 이 사실의 코드다.
    """


@lru_cache(maxsize=1)
def _library_version() -> str:
    """구현과 실행기의 버전.

    CTranslate2 를 함께 적는 이유는 그것이 실제 연산을 하기 때문이다. faster-whisper 만
    적으면 실행기가 바뀌었는데 값이 그대로인 구간이 생긴다(`rapidocr_backend` 가
    onnxruntime 을 함께 적는 것과 같은 이유).
    """
    parts: list[str] = []
    for package in ("faster-whisper", "ctranslate2"):
        try:
            parts.append(f"{package}={version(package)}")
        except PackageNotFoundError:  # 설치 경로에 따라 메타데이터가 없을 수 있다
            parts.append(f"{package}=unknown")
    return " ".join(parts)


def model_identifier(model_name: str, revision: str, compute_type: str) -> str:
    """봉투의 `versions.modelVersion` 에 실리는 값.

    **엔진 이름이 앞에 붙는다.** 이 필드는 결과마다 한 번 실려 정본에 남는데
    `small@int8` 만으로는 무엇이 만든 값인지 알 수 없다 — 같은 Whisper 가중치를 원본
    구현·CTranslate2 판·다른 런타임이 각각 돌리고 결과가 서로 다르다. `ocr` 이 같은
    모양으로 적는다(계약 §4.5 예시 `rapidocr/rapidocr3.9.2+onnxruntime1.29.0`).

    compute type 이 함께 들어가는 이유는 `float16` 과 `int8` 이 같은 모델의 다른 수치라
    같은 오디오에서 다른 문장이 나오기 때문이다.

    **리비전이 함께 들어간다.** 크기 이름은 저장소로 풀리고 그 저장소는 갱신되므로,
    이름만으로는 어느 가중치였는지 나중에 알 수 없다 (S15P21A501-187).
    """
    return f"{ENGINE_NAME}/{model_name}@{revision}+{compute_type}"


def speech_judgment(info: Any, config: AsrConfig) -> tuple[bool | None, float | None]:
    """엔진의 발화 판정 `(있었는가, 발화 오디오 길이 초)`.

    **빈 전사를 무음의 근거로 쓰지 않기 위해 있다.** 라이브러리는 임계에 걸린 구간과
    글자가 없는 구간을 스스로 버리므로, 전사가 비었다는 것은 "말이 없었다" 가 아니라
    "남은 문장이 없다" 다. 무음을 말할 수 있는 근거는 VAD 가 남긴 오디오 길이
    (`duration_after_vad`)뿐이다 — 0 이면 판정할 발화 자체가 없었다는 뜻이다.

    VAD 를 끄면 이 값은 클립 전체 길이라 판정이 아니다. 그때는 `None` 을 돌려준다.
    **모르는 것을 무음으로 적지 않는다** — 그 거짓은 정본에 남고 나중에 되돌릴 수 없다.
    """
    if not config.vad.enabled:
        return None, None
    remaining = getattr(info, "duration_after_vad", None)
    if remaining is None:
        # 라이브러리가 이 필드를 없애거나 이름을 바꾸면 판정을 지어내지 않는다.
        logger.warning("faster-whisper 가 duration_after_vad 를 주지 않았다: 발화 판정을 비운다")
        return None, None
    seconds = max(0.0, float(remaining))
    return seconds > 0.0, seconds


def resolve_compute_type(settings: Settings) -> str:
    """이 워커가 쓸 compute type. 설정이 있으면 그것, 없으면 장치 기본값이다."""
    if settings.asr_compute_type:
        return settings.asr_compute_type
    return _DEFAULT_COMPUTE_TYPE[detect_device(settings.device).resolved]


class FasterWhisperEngine:
    """`AsrEngine` 의 유일한 구현.

    **생성은 싸고 로딩은 따로다.** 생성자는 라이브러리와 모델 설정만 확인하고, 수 GB
    가중치는 `warm_up()`(기동) 또는 첫 `transcribe()` 에서 올린다. 둘을 합쳐 두면
    `capability_versions()` 가 엔진을 만들어 보는 것만으로 가중치를 내려받게 되는데,
    그 함수는 claim long-poll 한 바퀴마다·실패 기록마다 **동기로** 불리므로 로딩이
    이벤트 루프를 통째로 멈춘다(`vlm_metadata` 가 같은 이유로 `is_loaded` 를 둔다).

    첫 잡에서 내려받으면 그 시간이 통째로 그 잡의 처리 시간이 되고 내려받기 실패가 잡
    하나를 죽이므로, 로딩 자체는 기동 때 `warm_up()` 으로 미리 한다(`ocr` 의 `_warm_ocr`
    과 같은 판단).
    """

    def __init__(
        self,
        model_name: str,
        revision: str,
        compute_type: str,
        device: str,
        model_dir: Path | None,
    ) -> None:
        # **라이브러리 확인이 먼저다.** 둘 다 없는 워커에서 모델 미설정(일시)을 먼저
        # 신고하면, 재시도로는 절대 고쳐지지 않는 상태를 재시도 가능으로 적게 된다.
        # 없는 것 중 더 근본적인 사실을 말한다 — 이 이미지에 구현이 없다(영구).
        #
        # 임포트만 한다. `WhisperModel` 을 여기서 만들지 않는 것이 이 생성자가 싼 이유다.
        try:
            import faster_whisper  # noqa: F401
        except ImportError as exc:
            msg = f"faster-whisper 가 설치되지 않았다 (uv sync --group gpu): {exc}"
            raise AsrRuntimeMissingError(msg) from exc
        if not model_name:
            # 모델명에 기본값을 두지 않는 것은 `vlm_model` 과 같은 판단이다 — 결과를
            # 바꾸는 값이고 실측 후 확정 대상이라(FRD §11) 코드가 임의로 고르면 그게 곧
            # 근거 없는 동결이다. 고르지 않은 상태는 실패이지 기본값이 아니다.
            msg = "ASR 모델이 설정되지 않았다: NPICK_AI_ASR_MODEL"
            raise AsrModelUnavailableError(msg)
        self._model_name = model_name
        self._revision = revision
        self._compute_type = compute_type
        self._device = device
        self._model_dir = model_dir
        self._model: Any | None = None

    @property
    def name(self) -> str:
        return ENGINE_NAME

    @property
    def version(self) -> str:
        return _library_version()

    @property
    def model_version(self) -> str:
        """엔진·가중치·정밀도. 셋 다 있어야 같은 오디오의 결과를 재현할 수 있다.

        **로딩 여부와 무관하다.** 셋 다 설정에서 오므로 가중치를 올리기 전에도 같은
        값이다(`VlmClient.model_version` 이 로딩 후 commit SHA 로 바뀌는 것과 다르다).
        이 속성을 읽는 것만으로 로딩이 일어나지 않는다.
        """
        return model_identifier(self._model_name, self._revision, self._compute_type)

    @property
    def is_loaded(self) -> bool:
        """가중치가 준비됐는가. **조회 시 로딩하지 않는다**(`TransformersVlmClient` 와 같다)."""
        return self._model is not None

    def warm_up(self) -> str:
        """첫 잡 전에 가중치를 올린다. 실패는 기동 시점에 드러난다."""
        self._ensure_loaded()
        return f"engine={self.name} {self.version} model={self.model_version}"

    def _ensure_loaded(self) -> Any:
        """가중치를 올린다. 이미 올라와 있으면 그대로 돌려준다.

        **폴링 경로에서 불리면 안 된다.** 수 GB 로딩이 동기로 돌아 이벤트 루프를 멈춘다 —
        `jobs/registry._declared_version` 이 `is_loaded` 로 그것을 막는다.
        """
        if self._model is not None:
            return self._model
        from faster_whisper import WhisperModel

        logger.info(
            "ASR 가중치를 올린다: model=%s compute_type=%s device=%s",
            self._model_name,
            self._compute_type,
            self._device,
        )
        try:
            self._model = WhisperModel(
                self._model_name,
                device=self._device,
                compute_type=self._compute_type,
                download_root=str(self._model_dir) if self._model_dir is not None else None,
                # 기록만 하고 로딩에 넘기지 않으면 그 기록이 거짓이 된다.
                # 로컬 디렉터리를 가리키는 경우 라이브러리가 이 값을 무시한다.
                revision=self._revision or None,
            )
        except MemoryError:
            # **OOM 은 그대로 올려보낸다.** `transcribe()` 가 같은 예외를 같은 이유로
            # 올려보내고 `jobs/errors.classify` 가 `OUT_OF_MEMORY`(일시)로 옮긴다. 여기서
            # `MODEL_UNAVAILABLE` 로 싸면 VLM 이 먼저 VRAM 을 잡은 파드에서 같은 원인이
            # 로딩 시점이냐 인식 시점이냐에 따라 다른 코드로 정본에 남는다.
            raise
        except RuntimeError as exc:
            if _CUDA_OOM_MARKER in str(exc).lower():
                raise  # 같은 이유. classify 가 문자열로 CUDA OOM 을 알아본다.
            raise self._unavailable(exc) from exc
        except Exception as exc:
            # 가중치를 못 받았거나 이 장치에서 못 여는 compute type 이다. 둘 다 이
            # 클립의 문제가 아니므로 일시로 신고한다(계약 §9.2 `MODEL_UNAVAILABLE`).
            raise self._unavailable(exc) from exc
        return self._model

    def _unavailable(self, exc: Exception) -> AsrModelUnavailableError:
        msg = f"ASR 가중치를 준비하지 못했다 ({self._model_name}, {self._compute_type}): {exc}"
        return AsrModelUnavailableError(msg)

    def transcribe(self, media_path: Path, config: AsrConfig) -> Transcription:
        """디코드 → (VAD) → 인식. 실패의 종류를 갈라 던진다."""
        # **try 밖이다.** 로딩 실패는 인식 실행의 실패가 아니라 가중치의 문제이고
        # (`MODEL_UNAVAILABLE`·`OUT_OF_MEMORY`), 아래 `except Exception` 이 그것을
        # `AsrCallError` 로 덮으면 그 구분이 사라진다.
        model = self._ensure_loaded()
        audio = self._decode(media_path)
        try:
            segments, info = model.transcribe(
                audio,
                language=config.language,
                task=config.task,
                beam_size=config.decode.beam_size,
                # 단일 값이라 temperature fallback 이 돌지 않는다. 압축률·logprob 임계에
                # 걸린 구간을 온도를 올려 다시 만들면 근거가 더 약한 문장이 나온다.
                temperature=config.decode.temperature,
                condition_on_previous_text=config.decode.condition_on_previous_text,
                no_speech_threshold=config.decode.no_speech_threshold,
                log_prob_threshold=config.decode.log_prob_threshold,
                compression_ratio_threshold=config.decode.compression_ratio_threshold,
                word_timestamps=config.decode.word_timestamps,
                # **VAD 가 여기서 켜진다.** 라이브러리가 무음을 잘라 인식에 넣고
                # 타임스탬프는 원본 타임라인으로 되돌려 준다(docs/asr.md §4).
                vad_filter=config.vad.enabled,
                vad_parameters=_vad_parameters(config) if config.vad.enabled else None,
            )
            # generator 다. 여기서 소비해야 인식이 실제로 돌고, 예외도 여기서 난다.
            recognized = tuple(
                SpeechSegment(
                    start=float(segment.start),
                    end=float(segment.end),
                    text=str(segment.text),
                    avg_logprob=float(segment.avg_logprob),
                    no_speech_prob=float(segment.no_speech_prob),
                )
                for segment in segments
            )
            # 판정은 소비가 끝난 뒤에 읽는다. 라이브러리가 VAD 를 `transcribe()` 안에서
            # 먼저 돌려 `info` 를 채우지만, 그 순서에 기대지 않아도 되는 자리다.
            detected, speech_seconds = speech_judgment(info, config)
            return Transcription(
                segments=recognized,
                speech_detected=detected,
                speech_audio_seconds=speech_seconds,
            )
        except MemoryError:
            # `jobs/errors.classify` 가 `OUT_OF_MEMORY`(일시)로 옮긴다. 여기서 삼키면
            # 다른 파드에서 성공할 수 있는 실패가 일반 실패로 기록된다.
            raise
        except RuntimeError as exc:
            if _CUDA_OOM_MARKER in str(exc).lower():
                raise
            raise AsrCallError(f"음성 인식 실행이 실패했다: {exc}") from exc
        except Exception as exc:
            raise AsrCallError(f"음성 인식 실행이 실패했다: {exc}") from exc

    def _decode(self, media_path: Path) -> Any:
        """오디오를 16kHz 로 편다.

        인식과 **따로** 부르는 이유는 실패의 종류가 다르기 때문이다. 디코드 실패는 같은
        파일을 다시 열어도 같으므로 영구(`UNSUPPORTED_MEDIA`)이고, 인식 실패는 다음
        시도에서 성공할 수 있으므로 일시(`ASR_FAILED`)다. 한 덩어리로 잡으면 둘이 같은
        코드로 기록되고, 그러면 재시도가 고칠 수 있는지를 나중에 알 수 없다.

        오디오 트랙이 아예 없는 파일도 여기서 걸린다. **발화 미감지로 만들지 않는다** —
        트랙이 없는 것과 조용한 것은 다른 사실이고, 뒤엣것으로 적으면 정본이 거짓이 된다.
        """
        try:
            from faster_whisper.audio import decode_audio
        except ImportError as exc:
            msg = f"faster-whisper 가 설치되지 않았다 (uv sync --group gpu): {exc}"
            raise AsrRuntimeMissingError(msg) from exc
        try:
            return decode_audio(str(media_path), sampling_rate=SAMPLING_RATE)
        except Exception as exc:
            msg = f"오디오를 디코드할 수 없다: {media_path.name} ({exc})"
            raise MediaUnreadableError(msg) from exc


def _vad_parameters(config: AsrConfig) -> dict[str, Any]:
    """설정을 라이브러리의 VAD 파라미터 이름으로 옮긴다.

    이름을 그대로 쓰지 않고 여기서 옮기는 이유는 어댑터 경계다 — 라이브러리가 키
    이름을 바꿔도 설정 파일과 `config_version` 은 그대로여야 한다.
    """
    return {
        "threshold": config.vad.threshold,
        "min_speech_duration_ms": config.vad.min_speech_duration_ms,
        "max_speech_duration_s": config.vad.max_speech_duration_s,
        "min_silence_duration_ms": config.vad.min_silence_duration_ms,
        "speech_pad_ms": config.vad.speech_pad_ms,
    }


def shared_engine() -> FasterWhisperEngine:
    """프로세스에 하나만 두는 엔진. **가중치를 올리지는 않는다**(`warm_up()` 이 한다).

    **캐시가 요점이다.** 가중치는 수 GB 이고 VRAM 에 올라간다. 잡마다 새 인스턴스를
    쓰면 내려받기·로딩 비용을 매 잡이 내고 VRAM 이 두 벌 잡힌다(`ocr.shared_engine` 과
    같은 이유이며 그쪽보다 비용이 크다). 로딩 상태가 인스턴스에 붙어 있으므로 공유가
    곧 "한 번만 올린다" 다.
    """
    settings = get_settings()
    return _cached_engine(
        settings.asr_model,
        settings.asr_model_revision,
        resolve_compute_type(settings),
        detect_device(settings.device).resolved,
        settings.asr_model_dir,
    )


def build_engine(model_name: str, *, settings: Settings | None = None) -> FasterWhisperEngine:
    """설정 대신 인자로 모델을 골라 만든다. **후보 비교 도구(`report.py`) 전용이다.**

    `shared_engine()` 을 쓰지 않는 이유는 그것이 `NPICK_AI_ASR_MODEL` 에 묶여 있기
    때문이다 — 이 도구의 목적은 같은 입력으로 여러 모델을 돌리는 것이다. compute
    type·장치·캐시 경로는 그대로 설정에서 가져온다. 직접 생성자를 부르면 그 셋을
    빠뜨리게 되고, 그러면 실측 표의 처리 시간이 통째로 다른 조건의 것이 된다
    (`vlm_metadata.build_client` 와 같은 판단). 공유 캐시에는 넣지 않는다.
    """
    config = settings if settings is not None else get_settings()
    return FasterWhisperEngine(
        model_name,
        # 비교 도구는 리비전을 고정하지 않아도 돈다 — 능력 선언을 하지 않으므로
        # `registry` 의 가드에 걸릴 일이 없다. 설정에 있으면 그대로 쓴다.
        config.asr_model_revision,
        resolve_compute_type(config),
        detect_device(config.device).resolved,
        config.asr_model_dir,
    )


@lru_cache(maxsize=1)
def _cached_engine(
    model_name: str, revision: str, compute_type: str, device: str, model_dir: Path | None
) -> FasterWhisperEngine:
    """`maxsize=1` 인 이유는 VRAM 이다. 설정이 바뀌면 옛 인스턴스를 버린다.

    리비전도 캐시 키다. 빼면 같은 이름의 다른 가중치를 요청해도 옛 인스턴스가 나온다.
    """
    logger.info(
        "ASR 엔진을 만든다: model=%s@%s compute_type=%s device=%s",
        model_name,
        revision or "(미고정)",
        compute_type,
        device,
    )
    return FasterWhisperEngine(model_name, revision, compute_type, device, model_dir)
