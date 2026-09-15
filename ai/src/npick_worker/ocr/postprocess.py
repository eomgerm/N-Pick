"""엔진 출력을 원본 관측으로 옮긴다.

빈 원문 판정, confidence 반올림, 검색 토큰·text_key 생성, 미달 표시를 담당한다.
Frame 간 그룹화는 `merge.py`가 한다. 검색 토큰 해시는 다른 원문에서도 같을 수
있으므로 그룹 판정의 충분조건이 아니다. 그룹화 후에도 이 관측은 전부 보존한다.
"""

import hashlib
from collections.abc import Iterable, Sequence
from typing import Final

from npick_worker import korean_tokens
from npick_worker.ocr.engine import TextDetection
from npick_worker.ocr.models import (
    CONFIDENCE_DECIMALS,
    BoundingBox,
    KeyframeObservations,
    KeyframeRef,
    OcrObservation,
)

#: 토큰이 하나도 없는 원문(기호·잡음)의 `text_key` 접두. 원문 자체로 묶는다 —
#: 토큰이 비었다고 서로 다른 문구를 한 덩어리로 만들면 안 된다.
_RAW_KEY_PREFIX: Final[str] = "raw:"
_TOKEN_KEY_PREFIX: Final[str] = "tok:"
_KEY_HASH_LENGTH: Final[int] = 12


def text_key(raw_text: str, tokens: Sequence[str]) -> str:
    """같은 문구를 가리키는 관측이 공유하는 값.

    해시로 두는 이유는 이 값이 로그와 payload 에 실리기 때문이다. 원문을 그대로
    쓰면 긴 자막 한 줄이 키가 되어 읽기 어렵고, 프레임마다 반복되어 payload 가
    두 배가 된다.
    """
    if tokens:
        material = f"{_TOKEN_KEY_PREFIX}{' '.join(tokens)}"
    else:
        material = f"{_RAW_KEY_PREFIX}{korean_tokens.prepare(raw_text)}"
    digest = hashlib.sha256(material.encode("utf-8")).hexdigest()
    return digest[:_KEY_HASH_LENGTH]


def to_observations(
    keyframe: KeyframeRef,
    detections: Iterable[TextDetection],
    *,
    min_confidence: float,
) -> KeyframeObservations:
    """한 장의 엔진 출력을 그 장의 관측 묶음으로 만든다."""
    observations: list[OcrObservation] = []
    for detection in detections:
        if not detection.text.strip():
            # 상자는 잡았는데 읽어 낸 글자가 없다. `raw_text` 가 NOT NULL 이고,
            # 빈 문자열 행은 검색에도 근거 표시에도 쓸 수 없다.
            continue
        # **엔진이 준 그대로 담는다.** 판정에만 `strip()` 을 쓰고 값에는 쓰지 않는다 —
        # 앞뒤 공백을 떼는 것도 원문을 고치는 것이고, 컬럼 주석이 "절대 덮어쓰지
        # 않는다" 이다. 정규화가 필요한 곳(`tokens`·`text_key`)은 각자 한다.
        raw_text = detection.text
        tokens = korean_tokens.index_tokens(raw_text)
        confidence = round(detection.confidence, CONFIDENCE_DECIMALS)
        observations.append(
            OcrObservation(
                keyframe=keyframe,
                raw_text=raw_text,
                tokens=tokens,
                confidence=confidence,
                box=BoundingBox(points=tuple(detection.points)),
                # `<` 이다. 임계값과 같은 값은 검증된 쪽에 둔다 — 설정에 적은 수치가
                # "이 값부터 믿는다" 로 읽히는 것이 자연스럽다.
                unverified=confidence < min_confidence,
                text_key=text_key(raw_text, tokens),
            )
        )
    return KeyframeObservations(keyframe=keyframe, observations=tuple(observations))
