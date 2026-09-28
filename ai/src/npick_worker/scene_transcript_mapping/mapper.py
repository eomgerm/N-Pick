"""Pure mapping policy; no job API, model calls or storage access.

채택 규칙 자체는 여기 없다. 4단계가 소유하고(`npick_worker.transcript_selection`)
이 단계는 그것을 다시 돌린 뒤 장면에 잇는다 — 계약 §4.5 가 두 단계에 같은 규칙을
요구하므로 사본을 두지 않는다(`docs/contracts/job-api.md:945`).
"""

from collections.abc import Sequence
from dataclasses import dataclass

from npick_worker.transcript_selection.selector import (
    PRIORITY,
    Decision,
    Segment,
    Source,
    interval,
    select,
)

ALGORITHM_VERSION = "scene-transcript-mapping/v2"

#: 규칙을 옮긴 뒤에도 이 이름들로 import 하던 자리가 그대로 돌게 둔다.
__all__ = [
    "ALGORITHM_VERSION",
    "PRIORITY",
    "Decision",
    "MappingResult",
    "Scene",
    "SceneLinks",
    "Segment",
    "Source",
    "interval",
    "map_transcripts",
]


@dataclass(frozen=True, slots=True)
class Scene:
    index: int
    start: int
    end: int

    def __post_init__(self) -> None:
        interval(self.start, self.end)
        if type(self.index) is not int or self.index < 0:
            raise ValueError("invalid scene index")


@dataclass(frozen=True, slots=True)
class SceneLinks:
    index: int
    links: tuple[tuple[str, int], ...]  # (segment ID, overlap ms)


@dataclass(frozen=True, slots=True)
class MappingResult:
    segments: tuple[Segment, ...]
    decisions: tuple[Decision, ...]
    scenes: tuple[SceneLinks, ...]


def map_transcripts(scenes: Sequence[Scene], segments: Sequence[Segment]) -> MappingResult:
    """Run the shared adoption rule, then map selected speech onto scenes."""
    if not scenes or len({s.index for s in scenes}) != len(scenes):
        raise ValueError("expected nonempty unique scenes")
    selection = select(segments)
    links = tuple(
        SceneLinks(
            scene.index,
            tuple(
                (segment.id, overlap)
                for segment in selection.originals
                if segment.id in selection.selected
                and (overlap := min(scene.end, segment.end) - max(scene.start, segment.start)) > 0
            ),
        )
        for scene in sorted(scenes, key=lambda s: s.index)
    )
    return MappingResult(selection.originals, selection.decisions, links)
