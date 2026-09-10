"""OCR 엔진의 경계.

`ai/AGENTS.md` 가 "외부 모델 호출은 모듈 Protocol 어댑터 경계 뒤에 두고 호출부에
provider SDK 를 직접 노출하지 않는다" 로 정한다. `frame_extraction` 의
`FrameGrabber` 와 같은 자리다.

이 경계가 실제로 사는 것: 엔진 선정은 샘플 클립 한 편(800x450)의 실측으로 정했고
(docs/ocr.md §2), 1080p 방송분으로 다시 재면 뒤집힐 수 있다. 그때 바뀌는 것은
backend 파일 하나와 설정이지 `reader.py` 도 `jobs/` 도 아니다.
"""

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, runtime_checkable


@dataclass(frozen=True, slots=True)
class TextDetection:
    """엔진이 돌려준 글줄 하나. 아직 우리 어휘가 아니다.

    `confidence` 는 0~1 로 정규화된 값이어야 한다. 엔진마다 척도가 다르면 그 변환은
    backend 의 몫이다 — 여기서 밖으로 나가는 값은 언제나 같은 뜻이어야
    `ocr_observation.confidence` 의 `CHECK (confidence BETWEEN 0 AND 1)` 이 성립한다.
    """

    text: str
    confidence: float
    #: 원본 이미지 좌표계의 다각형. 검출기가 축소본에서 찾았더라도 되돌려 놓는다.
    points: tuple[tuple[float, float], ...]


@runtime_checkable
class OcrEngine(Protocol):
    """이미지 한 장에서 글자를 읽는다."""

    @property
    def name(self) -> str:
        """재현 튜플에 들어가는 구현 이름. 예: `rapidocr`"""
        ...

    @property
    def version(self) -> str:
        """구현과 **모델**의 버전.

        라이브러리 버전만으로는 부족하다. 같은 rapidocr 라도 인식 모델이 v4 인지
        v5 인지에 따라 읽는 글자가 달라진다.
        """
        ...

    def read(self, image_path: Path) -> tuple[TextDetection, ...]:
        """한 장을 읽는다. 글자가 없으면 빈 튜플이다.

        **낮은 신뢰도라고 걸러 내지 않는다.** 무엇을 `unverified` 로 볼지는 설정이
        정하고(`min_confidence`), 관측 자체는 전부 남긴다 — 티켓 제약이 "미달 결과는
        검색 후보에는 쓸 수 있다" 이므로 여기서 버리면 되돌릴 수 없다.
        """
        ...
