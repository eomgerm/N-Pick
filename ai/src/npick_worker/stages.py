"""FRD §5.1 Pipeline 단계 표의 전사(轉寫).

단계 구현은 여기에 없다. 이름·순서·필수 출력·실패 분류만 선언한다.
구현은 단계 이름과 같은 패키지에 있다 (예: `scene_detection/`).
이 표와 구현을 잇는 배선과 작업 수신 방식은 S15P21A501-70 에서 추가한다.
"""

from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Final


@dataclass(frozen=True, slots=True)
class StageSpec:
    order: int
    name: str
    required_output: str
    #: 치명 단계는 실패 시 검색 제공이 불가하다(FRD FR-PRC-002, FR-PRC-005).
    fatal: bool
    #: FRD §5.1 "실패 분류" 열의 원문. 임의의 enum 으로 재해석하지 않는다.
    failure_classification: str


STAGES: Final[tuple[StageSpec, ...]] = (
    StageSpec(1, "scene_detection", "scene boundary·index", True, "치명"),
    StageSpec(2, "frame_extraction", "복수 keyframe·thumbnail", True, "치명"),
    StageSpec(
        3,
        "vlm_metadata",
        "schema-valid metadata·confidence·frame evidence",
        False,
        "비치명, 누락 표시",
    ),
    StageSpec(
        4,
        "ocr",
        "frame별 verbatim·confidence·box·evidence",
        False,
        "비치명, 누락 표시",
    ),
    StageSpec(
        5,
        "transcript_selection",
        "provided/CC 선택 또는 ASR 필요 판정",
        False,
        "비치명",
    ),
    StageSpec(6, "asr", "segment start/end/text/confidence", False, "비치명"),
    StageSpec(
        7,
        "scene_transcript_mapping",
        "overlap 기반 scene segment 연결",
        False,
        "해당 신호 누락",
    ),
    StageSpec(
        8,
        "entity_extraction",
        "typed tag 후보·source·confidence·evidence",
        False,
        "비치명",
    ),
    StageSpec(
        9,
        "text_embedding",
        "searchable dense vector·model version",
        False,
        "비치명",
    ),
    StageSpec(
        10,
        "indexing",
        "BM25·dense·structured index document",
        True,
        "치명: 검색 불가",
    ),
)

STAGES_BY_NAME: Final[Mapping[str, StageSpec]] = MappingProxyType({s.name: s for s in STAGES})
