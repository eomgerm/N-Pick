"""`asr` 단계의 산출물 어휘.

계약(§4.5)이 요구하는 와이어 모양(`segmentId`·`s`·`e`·`t`·`sourceDetail`)은 여기 없다.
그것은 `jobs/models.py` 의 `AsrOutput` 이 만든다 — 이 모듈은 잡 API 를 모른다
(`ai/AGENTS.md`: 단계 구현은 순수 함수).
"""

from dataclasses import dataclass

#: `confidence` 의 소수 자리. `numeric(5,4)` 인 `ocr_observation.confidence` 와 맞춘다 —
#: 대사 신뢰도를 담을 칸은 아직 없지만 두 값이 다른 정밀도로 돌아다닐 이유도 없다.
CONFIDENCE_DECIMALS: int = 4


@dataclass(frozen=True, slots=True)
class AsrSegment:
    """인식한 발화 구간 하나."""

    #: 0 부터의 연번. `segmentId` 를 만드는 근거이고 **재시도해도 같아야 한다**
    #: (계약 §4.5: 이후 스냅샷에서도 기존 구간 ID 를 보존한다).
    index: int
    #: 원본 영상 기준 정수 ms. 시작 포함·종료 제외로 읽는다(FRD F-03 완료 기준).
    start_ms: int
    end_ms: int
    #: 인식한 그대로. 정규화·교정하지 않는다.
    text: str
    #: 0~1 로 옮긴 평균 로그 확률. **보정된 확률이 아니라 근사다**(`recognizer.py`).
    confidence: float
    #: 이 구간이 발화가 아닐 확률. 엔진이 준 값 그대로다.
    no_speech_prob: float

    @property
    def duration_ms(self) -> int:
        return self.end_ms - self.start_ms


@dataclass(frozen=True, slots=True)
class AsrResult:
    """한 미디어의 인식 결과와 그것을 만든 것의 정체."""

    segments: tuple[AsrSegment, ...]

    #: 재현 튜플의 세 축. 나머지 하나(`config_version`)는 아래 있다.
    engine: str
    engine_version: str
    model_version: str
    config_version: str

    #: **엔진이 구간을 하나도 내지 않았다.** 빈 `segments` 와 같은 말이 아니다 —
    #: 아래 `dropped_*` 로 전부 걸러져 비었을 수도 있다. 계약의
    #: `NO_SPEECH_DETECTED` 는 이 값이 참일 때만 쓴다(계약 §4.5: "실제 발화 미감지
    #: 판정에만"). BE 도 "빈 segments 만으로 사유를 만들지 않는다" 로 같은 구분을 한다.
    no_speech_detected: bool

    #: 엔진이 낸 구간 수. `len(segments)` 보다 크면 걸러진 것이 있다.
    raw_segment_count: int
    #: 글자가 없어 버린 구간 수. 계약이 `t` 를 비어 있지 않은 문자열로 요구한다.
    dropped_blank: int
    #: ms 로 반올림하니 길이가 0 이 된 구간 수. 계약이 `e > s` 를 요구한다.
    dropped_degenerate: int

    #: VAD 를 켜고 돌았는가. 실측 표를 나중에 해석하려면 있어야 한다.
    vad_enabled: bool

    @property
    def speech_ms(self) -> int:
        """발화로 판정된 총 길이. 무음 표본에서 이 값이 크면 환각을 의심한다."""
        return sum(segment.duration_ms for segment in self.segments)
