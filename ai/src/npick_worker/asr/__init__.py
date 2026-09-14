"""FRD §3 F-03 「자막·음성 처리」의 음성인식 (`stages.py` 의 `asr`, 비치명).

제공 자막·CC 가 덮지 못한 구간을 음성인식으로 보완한다(`docs/frd.md:122`). 토글 없이
자동으로 도는 fallback 이고, 사용자가 켜고 끄지 않는다.

이 모듈이 지키는 것 다섯.

- **VAD 가 실행기 안에 있다.** 무음·비발화 구간을 인식에 넣지 않는 일은 엔진 경계
  안쪽이고(`engine.py`), 밖으로 나오는 것은 발화로 판정된 구간뿐이다. 저장소에 별도
  VAD 구성은 없다 — faster-whisper 내장 Silero VAD 가 그 자리다.
- **빈 결과가 정상이다.** 발화가 없으면 구간 0 개로 정상 종료한다. 빈 구간을 채우려
  문장을 만들지 않는다.
- **발화 미감지와 실패를 가른다.** 계약의 `NO_SPEECH_DETECTED` 는 엔진이 구간을 하나도
  내지 않았을 때만이고, 실패(`ASR_FAILED`)·미배정·구현 없음(`NO_ADAPTER`)과 다른
  사실이다(계약 §4.5). VAD 오류나 실행 실패를 발화 미감지로 바꾸지 않는다.
- **자막을 대신하지 않는다.** 산출물은 후보일 뿐이고 제공 자막 → CC → ASR 우선순위의
  적용은 하류가 한다. 시간은 언제나 원본 영상 기준이다.
- **검증된 사실로 올리지 않는다.** ASR 근거는 기본 미검증이다. 신뢰도가 높다는 것과
  확인됐다는 것은 다르고(`docs/frd.md:158`), 인식된 날짜 문자열을 방송일·촬영일로
  승격하지 않는다(`docs/frd.md:157`).

순수 함수만 제공한다. 모델 호출은 `AsrEngine` Protocol 뒤에 있고, 미디어를 받아 오는
일과 pipeline run 배선은 `jobs/` 의 몫이다(`ai/AGENTS.md`).
"""

from npick_worker.asr.config import (
    DEFAULT_CONFIG_PATH,
    AsrConfig,
    DecodeParams,
    VadParams,
    get_default_config,
    load_config,
)
from npick_worker.asr.engine import (
    AsrCallError,
    AsrEngine,
    AsrModelUnavailableError,
    SpeechSegment,
)
from npick_worker.asr.models import CONFIDENCE_DECIMALS, AsrResult, AsrSegment
from npick_worker.asr.recognizer import transcribe_media

__all__ = [
    "CONFIDENCE_DECIMALS",
    "DEFAULT_CONFIG_PATH",
    "AsrCallError",
    "AsrConfig",
    "AsrEngine",
    "AsrModelUnavailableError",
    "AsrResult",
    "AsrSegment",
    "DecodeParams",
    "SpeechSegment",
    "VadParams",
    "get_default_config",
    "load_config",
    "transcribe_media",
]
