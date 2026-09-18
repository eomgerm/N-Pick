"""채택된 자막·CC 가 덮지 못한 구간. 순수 계산이고 정수 ms 만 다룬다."""

from collections.abc import Iterable, Sequence
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Range:
    """반열구간 `[s, e)`. 계약 §4.5 의 `candidateRanges` 원소와 같은 축이다."""

    s: int
    e: int

    def __post_init__(self) -> None:
        if type(self.s) is not int or type(self.e) is not int or not 0 <= self.s < self.e:
            raise ValueError("expected a nonempty nonnegative integer-ms range")


def uncovered_ranges(
    duration_ms: int, adopted: Iterable[tuple[int, int]], *, min_length_ms: int = 0
) -> tuple[Range, ...]:
    """`[0, duration_ms)` 에서 채택 구간을 뺀 여집합.

    **클램프는 정책이 아니라 출력 계약이다.** 자막 시간축과 `mediaDurationMs` 는 서로
    다른 측정값이다 — 자막은 등록 시 ffprobe `format.duration` 으로 상한을 검사하고
    `mediaDurationMs` 는 프레임 수 기반이다. 채택 구간의 끝이 클립 길이를 넘을 수
    있고, 클램프 없이 여집합을 구하면 음수 시작이나 길이 0 이하 구간이 나와 BE 가
    `e > s` 에서 거절한다.

    원본이 하나도 없으면 결과는 `[0, duration_ms)` 하나다. "자막이 전무하면 후보를
    반드시 하나 싣는다" 는 특례 분기가 아니라 여집합 계산의 자연 결과다 —
    `duration_ms` 가 항상 양수이기 때문이다(상류 `mediaDurationMs` 는 `gt=0`).
    """
    if type(duration_ms) is not int or duration_ms <= 0:
        raise ValueError("expected a positive integer clip duration")
    if type(min_length_ms) is not int or min_length_ms < 0:
        raise ValueError("expected a nonnegative integer minimum length")
    covered = _merge(
        sorted(
            (max(0, start), min(duration_ms, end))
            for start, end in adopted
            if max(0, start) < min(duration_ms, end)
        )
    )
    gaps: list[Range] = []
    cursor = 0
    for start, end in (*covered, (duration_ms, duration_ms)):
        if start - cursor >= max(1, min_length_ms):
            gaps.append(Range(cursor, start))
        cursor = max(cursor, end)
    return tuple(gaps)


def _merge(spans: Sequence[tuple[int, int]]) -> tuple[tuple[int, int], ...]:
    """맞닿거나 겹치는 구간을 하나로. 정렬된 입력을 전제한다."""
    merged: list[tuple[int, int]] = []
    for start, end in spans:
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return tuple(merged)
