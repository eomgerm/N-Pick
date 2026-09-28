"""FRD §3 F-03 「자막·음성 처리」의 음성인식 쪽 (`stages.py` 의 `asr`, 비치명).

제공 자막·CC 가 덮지 못한 구간을 음성인식으로 보완한다(`docs/frd.md:122`). 이 모듈이
지키는 것 넷.

- **없는 말을 만들지 않는다.** 엔진이 낸 구간만 옮긴다. 빈 구간을 채우려 문장을
  만들지 않고, 글자가 없는 구간은 버린다.
- **발화 미감지와 실패를 가른다.** 구간이 0 개인 것은 정상 종료이고 예외가 아니다.
  둘을 섞으면 "무음이었나 실패했나" 를 나중에 구분할 수 없다(계약 §4.5).
- **빈 결과를 무음의 근거로 쓰지 않는다.** 발화 미감지는 엔진의 판정
  (`Transcription.speech_detected`)일 때만이다. 목록이 비었다는 사실에서 추론하면
  "말은 있었는데 임계에 걸려 문장이 안 나온" 실행이 무음으로 기록된다.
- **시간은 원본 영상 기준이다.** VAD 가 무음을 잘라 낸 좌표를 되돌리는 일은 backend
  가 하고, 여기서는 초를 정수 ms 로 옮기기만 한다.
- **자막을 대신하지 않는다.** 이 단계의 산출물은 후보일 뿐이고 제공 자막 → CC → ASR
  우선순위의 적용은 하류의 일이다(계약 §4.5).

순수 함수만 있다. 모델 호출은 `AsrEngine` Protocol 뒤에 있고 미디어를 받아 오는 일과
pipeline run 배선은 `jobs/` 의 몫이다(`ai/AGENTS.md`).
"""

import math
from pathlib import Path

from npick_worker.asr.config import AsrConfig
from npick_worker.asr.engine import AsrEngine, SpeechSegment
from npick_worker.asr.models import CONFIDENCE_DECIMALS, AsrResult, AsrSegment

#: 초 → ms. 반올림 규칙을 한 곳에만 둔다.
_MS_PER_SECOND: int = 1000


def transcribe_media(media_path: Path, engine: AsrEngine, config: AsrConfig) -> AsrResult:
    """미디어 하나를 인식해 계약이 받을 수 있는 구간으로 옮긴다.

    영상 전체를 한 번에 돌린다. 미커버 구간만 잘라 돌리지 않는 이유가 둘이다 — 계약이
    구간별 실행기를 요구하지 않고(§4.5), 잘라 넣으면 문맥이 끊겨 경계에서 환각이 는다.
    무엇을 채택할지는 우선순위를 아는 하류가 정한다.
    """
    transcription = engine.transcribe(media_path, config)
    raw = transcription.segments

    segments: list[AsrSegment] = []
    dropped_blank = 0
    dropped_degenerate = 0
    for raw_segment in raw:
        text = raw_segment.text.strip()
        if not text:
            # 계약 §4.5 는 `t` 를 비어 있지 않은 문자열로 요구한다. 공백만 남은 구간을
            # 실어 보내면 BE 가 결과 **전체**를 거부한다(§9.2 `JOB_400_001`).
            dropped_blank += 1
            continue
        start_ms = _to_ms(raw_segment.start)
        end_ms = _to_ms(raw_segment.end)
        if end_ms <= start_ms:
            # 계약이 `e > s` 를 요구한다. 1ms 를 늘려 통과시키지 않는다 — 없는 길이를
            # 지어내는 것이고, 1ms 미만의 "발화" 는 어차피 사람이 들을 수 있는 말이 아니다.
            dropped_degenerate += 1
            continue
        segments.append(
            AsrSegment(
                index=len(segments),
                start_ms=start_ms,
                end_ms=end_ms,
                text=text,
                confidence=_confidence(raw_segment),
                no_speech_prob=_clamp(raw_segment.no_speech_prob),
            )
        )

    return AsrResult(
        segments=tuple(segments),
        engine=engine.name,
        engine_version=engine.version,
        model_version=engine.model_version,
        config_version=config.version_id,
        # **엔진이 "발화가 없었다" 고 판정했을 때만 참이다.** 목록이 비었다는 사실에서
        # 추론하지 않는다 — 엔진은 임계에 걸린 구간을 스스로 버리므로 말이 있어도 목록이
        # 빌 수 있고(`Transcription`), 그것을 무음으로 적으면 정본이 거짓이 된다.
        # 판정이 없으면(`None`, VAD off) 아무 말도 하지 않는다.
        #
        # `not segments` 를 함께 두는 것은 추론이 아니라 모순 방지다. 구간을 실어 보내면서
        # "발화가 없었다" 고 적으면 봉투 하나가 스스로 어긋난다.
        no_speech_detected=transcription.speech_detected is False and not segments,
        raw_segment_count=len(raw),
        dropped_blank=dropped_blank,
        dropped_degenerate=dropped_degenerate,
        vad_enabled=config.vad.enabled,
        vad_speech_ms=_optional_ms(transcription.speech_audio_seconds),
    )


def _to_ms(seconds: float) -> int:
    """초를 정수 ms 로. 음수는 0 으로 막는다.

    엔진이 VAD 여유(`speech_pad_ms`)를 붙이다가 0 앞으로 넘어가는 경우가 있다. 계약은
    `s >= 0` 을 요구하고, 음수 시각은 어느 프레임과도 겹치지 않아 장면 연결에서 조용히
    사라진다.
    """
    return max(0, round(seconds * _MS_PER_SECOND))


def _optional_ms(seconds: float | None) -> int | None:
    """판정이 없으면 `None` 을 그대로 둔다. 0 으로 바꾸면 "발화 0 초" 라는 판정이 된다."""
    return None if seconds is None else _to_ms(seconds)


def _confidence(segment: SpeechSegment) -> float:
    """평균 로그 확률을 0~1 로 옮긴다.

    **보정된 확률이 아니다.** `exp(avg_logprob)` 은 토큰 확률의 기하평균이라 "이 문장이
    맞을 확률" 과 같지 않다. 그래도 싣는 이유는 F-04 가 근거에 확인 가능한 값을 요구하고,
    구간 사이의 상대 비교(어느 대사가 더 불확실한가)에는 쓸 수 있기 때문이다.
    이 값 하나로 검증된 사실이 되지 않는다 — ASR 근거는 언제나 미검증이다
    (`docs/frd.md:158`).
    """
    return round(_clamp(math.exp(segment.avg_logprob)), CONFIDENCE_DECIMALS)


def _clamp(value: float) -> float:
    """0~1 밖의 값을 막는다. 확률 칸의 `CHECK (BETWEEN 0 AND 1)` 이 그것을 요구한다."""
    return min(1.0, max(0.0, value))
