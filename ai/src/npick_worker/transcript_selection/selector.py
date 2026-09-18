"""Pure adoption policy; no job API, model calls or storage access.

Stage 4 owns this rule and stage 6 re-runs it. The job contract requires both to
follow the same one — "두 단계는 위의 같은 채택 규칙(제공 자막 → CC → ASR, 채택된
상위 출처만 제외 근거)을 따라야 하며, 어긋나면 같은 run 안에서 CC 채택 여부가
갈린다" (`docs/contracts/job-api.md:945`). One implementation is what enforces it;
two copies would drift silently, changing only whether CC is adopted.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Literal

Source = Literal["uploaded", "embedded", "asr"]
PRIORITY = {"uploaded": 0, "embedded": 1, "asr": 2}

#: 이 규칙의 재현 식별자. 두 단계의 `versions.detail` 이 같은 키로 이 값을 실어
#: 사람이 둘을 눈으로 대조할 수 있게 한다. 규칙이 바뀌면 두 단계의
#: `stageVersion` 이 함께 움직여야 한다(계약 §7).
SELECTION_VERSION = "transcript-selection/v1"


def interval(start: int, end: int) -> None:
    if type(start) is not int or type(end) is not int or not 0 <= start < end:
        raise ValueError("expected a nonempty nonnegative integer-ms interval")


@dataclass(frozen=True, slots=True)
class Segment:
    id: str
    start: int
    end: int
    text: str
    source: Source

    def __post_init__(self) -> None:
        interval(self.start, self.end)
        if not self.id or not self.text.strip() or self.source not in PRIORITY:
            raise ValueError("invalid transcript segment")


@dataclass(frozen=True, slots=True)
class Decision:
    segment_id: str
    selected: bool
    conflicts: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class Selection:
    #: `(start, end, priority, id)` 로 정렬된 원본 전체. 제외된 것도 빠지지 않는다.
    originals: tuple[Segment, ...]
    #: `originals` 와 같은 순서.
    decisions: tuple[Decision, ...]
    #: 채택된 구간 ID. **여기 담아 돌려주는 이유**는 계약 §4.5 가 제외 근거를
    #: "채택된 상위 출처만" 으로 못 박았기 때문이다. 4단계는 커버리지 계산에,
    #: 6단계는 장면 연결에 같은 집합을 쓴다 — 양쪽에서 다시 유도하지 않는다.
    selected: frozenset[str]


def select(segments: Sequence[Segment]) -> Selection:
    """Exclude whole lower-priority originals that overlap an adopted higher source.

    Only selected higher-priority originals are conflict evidence. An excluded
    subtitle cannot block ASR from supplementing a gap. Overlapping lower-priority
    originals are still excluded whole, without fabricating partial utterances.
    """
    if len({s.id for s in segments}) != len(segments):
        raise ValueError("duplicate segment ID")
    originals = tuple(sorted(segments, key=lambda s: (s.start, s.end, PRIORITY[s.source], s.id)))
    decisions_by_id: dict[str, Decision] = {}
    selected: set[str] = set()
    # Resolve higher sources first, even when their timestamps start later.
    for segment in sorted(originals, key=lambda s: PRIORITY[s.source]):
        conflicts = tuple(
            other.id
            for other in originals
            if other.id in selected
            and PRIORITY[other.source] < PRIORITY[segment.source]
            and max(segment.start, other.start) < min(segment.end, other.end)
        )
        decisions_by_id[segment.id] = Decision(segment.id, not conflicts, conflicts)
        if not conflicts:
            selected.add(segment.id)
    return Selection(
        originals=originals,
        decisions=tuple(decisions_by_id[s.id] for s in originals),
        selected=frozenset(selected),
    )
