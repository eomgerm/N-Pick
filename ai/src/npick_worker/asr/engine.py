"""ASR 엔진의 경계.

`ai/AGENTS.md` 가 "외부 모델 호출은 모듈 Protocol 어댑터 경계 뒤에 두고 호출부에
provider SDK 를 직접 노출하지 않는다" 로 정한다. `ocr/engine.py` 의 `OcrEngine`,
`vlm_metadata/client.py` 의 `VlmClient` 와 같은 자리다.

이 경계가 실제로 사는 것 둘.

- **모델 선정이 아직 열려 있다.** 엔진은 faster-whisper 로 두되(의존성·smoke 테스트가
  이미 그것을 전제한다) 모델 크기·compute type 은 실측 비교가 끝나야 정해진다. 그때
  바뀌는 것은 backend 파일과 설정이지 `recognizer.py` 도 `jobs/` 도 아니다.
- **VAD 가 이 경계 안쪽이다.** 티켓이 요구하는 "ASR 실행기 내부의 VAD·무음 처리" 가
  구현체의 성질이므로, 밖으로 나오는 것은 언제나 **발화로 판정된 구간**뿐이다.
"""

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, runtime_checkable

from npick_worker.asr.config import AsrConfig


@dataclass(frozen=True, slots=True)
class SpeechSegment:
    """엔진이 돌려준 발화 구간 하나. 아직 우리 어휘가 아니다.

    시간은 **원본 영상 기준 초**다. VAD 로 무음을 잘라 낸 좌표를 그대로 내보내면 뒤로
    갈수록 실제 시각과 벌어지고, 그 오차는 장면 연결에서야 드러난다. 되돌려 놓는 일은
    backend 의 몫이다 — 여기서 밖으로 나가는 값은 언제나 같은 뜻이어야 한다.
    """

    start: float
    end: float
    #: 인식한 그대로. 정규화·교정하지 않는다.
    text: str
    #: 이 구간 토큰의 평균 로그 확률. 0 이하다.
    avg_logprob: float
    #: 이 구간이 발화가 아닐 확률. 0~1.
    no_speech_prob: float


class AsrModelUnavailableError(RuntimeError):
    """가중치를 준비하지 못했거나 모델이 설정되지 않았다.

    `NO_ADAPTER`(영구)와 갈라야 한다 — 계약 §9.2 의 `MODEL_UNAVAILABLE` 은 **일시**다.
    캐시 볼륨이 안 붙었거나 내려받기가 실패한 것이라 다른 파드에서 성공할 수 있다.
    번역은 경계인 `jobs/registry` 가 한다. 이 모듈은 잡 API 를 모른다.
    """


class AsrCallError(RuntimeError):
    """인식을 끝내지 못했다. 런타임 오류·타임아웃 같은 **실행**의 실패다.

    빈 결과와 갈라야 한다. 발화가 없어서 구간이 0 개인 것은 정상 종료이고
    (`NO_SPEECH_DETECTED`), 이쪽은 실패다(`ASR_FAILED`). 둘이 같은 코드로 기록되면
    "무음이었나 실패했나" 를 나중에 구분할 수 없다 — 티켓의 완료 조건이 정확히 그
    구분을 요구한다.
    """


@runtime_checkable
class AsrEngine(Protocol):
    """미디어 하나에서 발화 구간을 읽는다."""

    @property
    def name(self) -> str:
        """재현 튜플에 들어가는 구현 이름. 예: `faster-whisper`"""
        ...

    @property
    def version(self) -> str:
        """구현과 실행기의 버전. 가중치 버전은 `model_version` 이 따로 말한다."""
        ...

    @property
    def model_version(self) -> str:
        """가중치의 식별자. 예: `large-v3-turbo@float16`

        런타임 버전만으로는 부족하다. 같은 라이브러리라도 모델 크기·compute type 이
        바뀌면 같은 오디오에서 다른 문장이 나온다(`VlmClient.model_version` 과 같은 이유).
        """
        ...

    def transcribe(self, media_path: Path, config: AsrConfig) -> tuple[SpeechSegment, ...]:
        """발화 구간을 돌려준다. 발화가 없으면 빈 튜플이다.

        **빈 튜플을 예외로 만들지 않는다.** 무음 영상에서 아무것도 나오지 않는 것은
        정상이고, 빈 구간을 채우려 문장을 만드는 것이 티켓이 금지한 일이다.

        Raises:
            AsrModelUnavailableError: 가중치를 준비하지 못했다(일시).
            AsrCallError: 인식 실행이 실패했다(일시).
            MediaUnreadableError: 오디오를 디코드할 수 없다(영구).
        """
        ...
