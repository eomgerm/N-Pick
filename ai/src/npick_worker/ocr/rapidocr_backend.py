"""`OcrEngine` 의 rapidocr(onnxruntime) 구현.

**이 파일 밖으로 rapidocr 타입이 나가지 않는다.** 나가면 엔진 교체가 단계 전체를
건드리는 일이 된다(`ai/AGENTS.md` 의 어댑터 경계).

왜 rapidocr 인가는 `docs/ocr.md` §2 의 실측 표가 답한다. 요약하면 인식 모델이
paddleocr 의 한국어 모델(`korean_PP-OCRv5_rec_mobile`)과 **같은 것**이고, 그것을
paddlepaddle 도 torch 도 없이 onnxruntime 으로 돌린다.
"""

import logging
from functools import lru_cache
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any, Final

from npick_worker.ocr.config import OcrConfig, get_default_config
from npick_worker.ocr.engine import TextDetection
from npick_worker.settings import get_settings

logger = logging.getLogger(__name__)

ENGINE_NAME: Final[str] = "rapidocr"

#: 엔진이 준 신뢰도를 그대로 통과시키기 위한 값. rapidocr 는 기본이 0.5 라 그 아래를
#: **말없이 버린다.** 그러면 티켓 제약("미달 결과는 검색 후보로 쓸 수 있다")과
#: `ocr_observation.raw_text` 의 "절대 덮어쓰지 않는다" 가 함께 깨진다.
#: 설정 키로 열지 않는 이유가 이것이다 — toml 한 줄로 관측이 사라져서는 안 된다.
_KEEP_EVERY_DETECTION: Final[float] = 0.0

#: **원본 해상도로 읽기 위한 값.** `Det.limit_side_len` 과 다른 축이다.
#:
#: rapidocr 는 검출기 리사이즈 **앞에** 전체 이미지 전처리를 한 번 더 돌린다
#: (`rapidocr/main.py` 의 `preprocess_img`). 기본값이 `max_side_len=2000` 이라 긴 변이
#: 그보다 크면 이미지 자체를 줄이고, **인식 조각을 그 줄인 이미지에서 잘라낸다**
#: (같은 파일의 `detect_and_crop` → `crop_text_regions`). 상자 좌표는 원본으로
#: 복원되지만 인식에 들어간 픽셀은 돌아오지 않는다.
#:
#: 실측: 3840x2160 → 1984x1120, 2560x1440 → 1984x1120. 1920x1080 은 그대로다.
#: FRD `docs/frd.md:131` 의 "축소된 대표 이미지 대신 원본 해상도의 프레임" 은
#: 해상도에 조건이 붙지 않으므로 이 전처리를 끈다.
#:
#: `_KEEP_EVERY_DETECTION` 과 같은 이유로 설정 키가 아니다 — toml 한 줄로 FRD 요구가
#: 깨지는 길을 두지 않는다. 검출 입력 크기를 조절하고 싶으면 `det_limit_side_len`
#: 이 그 자리다(그것은 "찾아내는가" 만 바꾸고 읽는 픽셀은 안 바꾼다).
_READ_AT_ORIGINAL_RESOLUTION: Final[bool] = False


class OcrModelUnavailableError(RuntimeError):
    """가중치를 준비하지 못했다. 일시 오류다 — 다른 파드나 다음 시도에서 성공할 수 있다.

    rapidocr 는 첫 사용 시 모델을 내려받는다. 캐시 볼륨이 없거나 네트워크가 막히면
    여기서 걸린다. 계약 §9.2 의 `MODEL_UNAVAILABLE` 로 번역되는 것은 `jobs/registry`
    의 몫이고, 이 모듈은 잡 API 를 모른다.
    """


class OcrReadError(RuntimeError):
    """이미지를 읽지 못했다. 파일이 깨졌거나 이미지가 아니다."""


@lru_cache(maxsize=1)
def _library_version() -> str:
    """구현과 실행기의 버전.

    onnxruntime 을 함께 적는 이유는 그것이 실제 연산을 하기 때문이다. rapidocr 만
    적으면 실행기가 바뀌었는데 값이 그대로인 구간이 생긴다(`frame_extraction` 이
    PyAV 와 numpy 를 함께 적는 것과 같은 이유).
    """
    parts = []
    for package in ("rapidocr", "onnxruntime"):
        try:
            parts.append(f"{package}{version(package)}")
        except PackageNotFoundError:  # 설치되지 않은 채로 호출된 경우
            parts.append(f"{package}?")
    return "+".join(parts)


def _engine_params(config: OcrConfig) -> dict[str, Any]:
    """설정을 rapidocr 의 키로 옮긴다. 값은 하나도 여기서 만들지 않는다."""
    from rapidocr import LangDet, LangRec, ModelType, OCRVersion

    params: dict[str, Any] = {
        # 검출·인식 모델 선택.
        "Det.lang_type": LangDet(config.det_lang),
        "Det.ocr_version": OCRVersion(config.ocr_version),
        "Det.model_type": ModelType(config.det_model_type),
        "Rec.lang_type": LangRec(config.rec_lang),
        "Rec.ocr_version": OCRVersion(config.ocr_version),
        "Rec.model_type": ModelType(config.rec_model_type),
        # 검출 파라미터.
        "Det.limit_type": config.det_limit_type,
        "Det.limit_side_len": float(config.det_limit_side_len),
        "Det.thresh": config.det_thresh,
        "Det.box_thresh": config.det_box_thresh,
        "Det.unclip_ratio": config.det_unclip_ratio,
        "Det.use_dilation": config.det_use_dilation,
        # 방향 분류기를 쓰지 않는다. 뉴스 화면의 글자는 뒤집혀 있지 않고, 켜면
        # 모델이 하나 더 붙어 장당 시간이 늘면서 잘못 뒤집는 경우가 생긴다.
        "Global.use_cls": False,
        "Global.text_score": _KEEP_EVERY_DETECTION,
        "Global.use_preprocess_img": _READ_AT_ORIGINAL_RESOLUTION,
    }
    model_dir = get_settings().ocr_model_dir
    if model_dir is not None:
        # 기본값은 site-packages 안이다. 컨테이너에서는 이미지 레이어에 쓰게 되므로
        # 매 기동마다 다시 받는다. Dockerfile 이 잡아 둔 캐시 볼륨으로 돌린다.
        params["Global.model_root_dir"] = str(model_dir)
    return params


class RapidOcrEngine:
    """`OcrEngine` 구현. 생성 시 모델을 올린다."""

    def __init__(self, config: OcrConfig | None = None) -> None:
        settings = config if config is not None else get_default_config()
        # 지연 임포트. rapidocr 는 onnxruntime·cv2 를 끌어오고 그건 헬스체크만 하는
        # 프로세스가 낼 비용이 아니다(`scene_detection` 의 cv2 와 같은 이유).
        from rapidocr import RapidOCR

        try:
            self._engine = RapidOCR(params=_engine_params(settings))
        except Exception as exc:  # 가중치 준비 실패를 일시 오류로 번역한다
            msg = f"OCR 모델을 준비하지 못했다: {type(exc).__name__}"
            raise OcrModelUnavailableError(msg) from exc

    @property
    def name(self) -> str:
        return ENGINE_NAME

    @property
    def version(self) -> str:
        return _library_version()

    def read(self, image_path: Path) -> tuple[TextDetection, ...]:
        # `Any` 로 받는다. rapidocr 의 `__call__` 은 부분 실행(검출만·인식만) 반환형까지
        # 합친 union 을 선언하는데, 우리는 언제나 세 단계를 다 도는 호출만 한다.
        # 어댑터 경계에서 한 번 좁히고 아래에서 우리 타입으로 옮긴다.
        result: Any
        try:
            result = self._engine(str(image_path))
        except (MemoryError, OSError):
            # **감싸지 않고 그대로 올린다.** 이 둘은 이미지의 결함이 아니라 실행 환경의
            # 사정이다. `OcrReadError` 로 감싸면 `jobs/registry` 가
            # `UnsupportedMediaError` 로 옮기고 계약 §9.2 의 `UNSUPPORTED_MEDIA`(영구)가
            # 되어 attempts 가 동결된다 — 더 큰 파드에서는 성공했을 잡이 "이 JPEG 을 열
            # 수 없다" 로 기록되는 것이다. 그대로 올리면 `jobs/errors.classify` 가
            # MemoryError → OUT_OF_MEMORY(재시도 가능), OSError → MEDIA_UNAVAILABLE 로
            # 옮긴다. 깨진 이미지는 여기 오지 않는다 — rapidocr 는 그 경우
            # `LoadImageError`(맨 Exception 하위)를 던지므로 아래 분기가 받는다.
            raise
        except Exception as exc:
            msg = f"이미지를 읽지 못했다: {image_path.name}"
            raise OcrReadError(msg) from exc

        if result is None or result.txts is None:
            # 글자가 없는 프레임에서 정상적으로 나오는 값이다. 뉴스 영상의 절반쯤이
            # 여기 해당한다(샘플 23장 중 12장).
            return ()

        return tuple(
            TextDetection(
                text=text,
                confidence=float(result.scores[index]),
                points=tuple((float(x), float(y)) for x, y in result.boxes[index]),
            )
            for index, text in enumerate(result.txts)
        )


@lru_cache(maxsize=1)
def shared_engine(config: OcrConfig) -> RapidOcrEngine:
    """프로세스 하나에 엔진 하나. **엔진이 필요한 곳은 전부 이것을 쓴다.**

    det+rec ONNX 세션 생성이 실측 0.4초(개발 장비)~15초(22코어 CPU, 폴링 부하 시)다.
    가중치가 캐시돼 있어도 세션은 매번 새로 만들어지므로, 캐시하지 않으면 부르는 쪽마다
    그 비용을 낸다 — 잡마다
    (`reader._default_engine`), 그리고 claim long-poll 한 바퀴마다
    (`jobs/registry._declared_version`). 뒤엣것은 OCR 잡을 한 건도 받지 않는 워커도
    영구히 내고, 동기 호출이라 이벤트 루프를 그대로 막는다.

    캐시가 있어야 `jobs/registry._warm_ocr` 의 "모델을 미리 올린다" 가 성립한다. 그것이
    만든 인스턴스를 첫 잡이 그대로 받기 때문이다. 캐시가 없으면 워밍업으로 앞당겨지는
    것은 가중치 내려받기뿐이고 세션 생성은 모든 잡이 낸다.

    **실패는 캐시되지 않는다**(`lru_cache` 의 동작). 가중치를 준비하지 못한 워커는 다음
    호출에서 다시 시도하고, 그동안 `capability_versions` 가 ocr 를 목록에서 뺀다.

    `maxsize=1`: 워커는 동봉 설정 하나로만 돈다. 임계값 실험이 다른 설정을 주면 새로
    만들고 앞엣것을 버린다 — 느릴 뿐 틀리지 않는다.

    **공유 인스턴스다.** `maxConcurrentStages=1` 이고 `read_keyframes` 가 동기라 지금은
    한 번에 하나만 쓴다. 동시 실행을 열 때 rapidocr 세션의 스레드 안전성을 먼저 확인할 것.
    """
    return RapidOcrEngine(config)
