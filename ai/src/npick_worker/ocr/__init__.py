"""FRD §3 F-03 화면 글자 읽기 (`stages.py` 3단계 `ocr`, 비치명).

`frame_extraction` 이 뽑은 keyframe 에서 화면 속 글자(뉴스 자막·현판·배너)를 읽어
**원문·신뢰도·위치·해당 keyframe** 을 남긴다(`docs/frd.md:123`). 임계값은 전부
버전이 붙은 설정에 있다(`config/ocr.v1.toml`).

네 가지를 이 모듈이 지킨다.

- **추출한 keyframe 을 읽는다.** 영상에서 프레임을 다시 뽑지 않는다. FRD 가 "OCR 은
  추출한 키프레임을 대상으로 수행한다" 로 정했고(`docs/frd.md:131`), 다시 뽑으면
  `keyframe` 행이 가리키는 이미지와 읽은 이미지가 달라질 수 있다. 그 프레임은
  `frame_extraction` 이 **원본 해상도로** 저장한 것이라 같은 문장의 "축소된 대표
  이미지 대신 원본 해상도의 프레임" 요구도 함께 지켜진다.
- **관측을 합치지 않는다.** 프레임 사이의 같은 문구도 각자 행으로 남는다. 묶어야
  하는 소비자는 `text_key` 로 묶고, 그래도 원본 관측과 keyframe 이 그대로 있다
  (`postprocess.py` 의 판단 근거).
- **미달 결과를 버리지 않는다.** `min_confidence` 미만은 `unverified` 로 **표시만**
  한다. 검색 후보로는 쓸 수 있어야 하고, 높은 confidence 만으로 verified 가 되지도
  않는다 — 그 판단은 `tag_evidence.verification_status` 의 몫이지 이 단계가 아니다
  (`docs/frd.md:151`).
- **경로를 만들지 않는다.** 이미지 바이트를 가져오는 일도, 결과를 올리는 일도 하지
  않는다. 이 모듈은 `pipeline_run_id` 도 미디어 루트도 모른다.

이 모듈은 순수 함수만 제공한다. 입력 내려받기와 pipeline run 배선은 `jobs/` 의 몫이다.
"""

from npick_worker.ocr.config import (
    DEFAULT_CONFIG_PATH,
    OcrConfig,
    get_default_config,
    load_config,
)
from npick_worker.ocr.engine import OcrEngine, TextDetection
from npick_worker.ocr.models import (
    CONFIDENCE_DECIMALS,
    BoundingBox,
    KeyframeObservations,
    KeyframeRef,
    OcrObservation,
    OcrResult,
)
from npick_worker.ocr.postprocess import text_key, to_observations
from npick_worker.ocr.rapidocr_backend import (
    OcrModelUnavailableError,
    OcrReadError,
    RapidOcrEngine,
    shared_engine,
)
from npick_worker.ocr.reader import read_keyframes

__all__ = [
    "CONFIDENCE_DECIMALS",
    "DEFAULT_CONFIG_PATH",
    "BoundingBox",
    "KeyframeObservations",
    "KeyframeRef",
    "OcrConfig",
    "OcrEngine",
    "OcrModelUnavailableError",
    "OcrObservation",
    "OcrReadError",
    "OcrResult",
    "RapidOcrEngine",
    "TextDetection",
    "get_default_config",
    "load_config",
    "read_keyframes",
    "shared_engine",
    "text_key",
    "to_observations",
]
