"""엔진 출력을 `ocr_observation` 행으로 옮긴다.

여기서 하는 일이 넷이고, **하지 않는 일이 하나** 다.

하는 일

1. 빈 원문을 버린다. `raw_text` 가 `NOT NULL` 이고, 글자가 없는 상자는 관측이 아니다.
2. confidence 를 넷째 자리로 맞춘다(`numeric(5,4)`).
3. Kiwi 색인 토큰을 만든다(`tokens`). 규칙은 질의 쪽과 같은 `korean_tokens` 다.
4. `min_confidence` 미만을 `unverified` 로 표시한다. **버리지 않는다.**

하지 않는 일 — **관측을 병합하지 않는다.**

티켓은 "frame 간 동일·유사 문구를 병합하는 경우에도 원본 OCR 관측과 해당 keyframe
으로 역추적 가능하도록" 을 요구한다. 병합을 하고 되돌아갈 길을 두는 방법과, 애초에
합치지 않는 방법이 있는데 후자를 골랐다. 근거 셋:

- **담을 곳이 없다.** `ocr_observation` 에 그룹을 가리킬 컬럼이 없고, baseline
  마이그레이션은 "별도 이력·잠금 테이블" 을 금지한다. 병합 결과를 저장하려면
  스키마가 먼저 바뀌어야 한다.
- **원문은 덮어쓰지 않는다.** 컬럼 주석이 그렇게 적혀 있다. 세 프레임에서 읽은
  같은 현판을 하나로 줄이면 나머지 둘의 `bounding_box_json`·`confidence` 가 사라지고,
  그건 "역추적 가능" 의 반대다.
- **소비자가 필요할 때 묶을 수 있다.** 같은 문구인지는 `text_key` 로 판정된다 —
  색인 토큰이 같으면 같은 값이다. 검색이 쓰는 것과 같은 정규화라 별도 임계값이
  필요 없다.

`text_key` 가 유사도 임계값이 아니라 토큰 일치인 것도 의도다. 유사도로 묶으려면
숫자를 하나 정해야 하는데 그 숫자에 실측 근거가 없다(FRD §11). 토큰 일치는
대소문자·전각·구두점·띄어쓰기 차이를 이미 흡수하므로 "동일 문구" 는 전부 잡는다.
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
        raw_text = detection.text.strip()
        if not raw_text:
            # 상자는 잡았는데 읽어 낸 글자가 없다. `raw_text` 가 NOT NULL 이고,
            # 빈 문자열 행은 검색에도 근거 표시에도 쓸 수 없다.
            continue
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
