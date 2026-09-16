"""Pure mapping policy; no job API, model calls or storage access."""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Literal

Source = Literal["uploaded", "embedded", "asr"]
PRIORITY = {"uploaded": 0, "embedded": 1, "asr": 2}
ALGORITHM_VERSION = "scene-transcript-mapping/v1"


def _interval(start: int, end: int) -> None:
    if type(start) is not int or type(end) is not int or not 0 <= start < end:
        raise ValueError("expected a nonempty nonnegative integer-ms interval")


@dataclass(frozen=True, slots=True)
class Scene:
    index: int
    start: int
    end: int

    def __post_init__(self) -> None:
        _interval(self.start, self.end)
        if type(self.index) is not int or self.index < 0:
            raise ValueError("invalid scene index")


@dataclass(frozen=True, slots=True)
class Segment:
    id: str
    start: int
    end: int
    text: str
    source: Source

    def __post_init__(self) -> None:
        _interval(self.start, self.end)
        if not self.id or not self.text.strip() or self.source not in PRIORITY:
            raise ValueError("invalid transcript segment")


@dataclass(frozen=True, slots=True)
class Decision:
    segment_id: str
    selected: bool
    conflicts: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class MappingResult:
    segments: tuple[Segment, ...]
    decisions: tuple[Decision, ...]
    # scene index, followed by (segment ID, overlap ms) links.
    scenes: tuple[tuple[int, tuple[tuple[str, int], ...]], ...]


def map_transcripts(scenes: Sequence[Scene], segments: Sequence[Segment]) -> MappingResult:
    """Exclude whole lower-priority originals, then map selected speech only.

    Every higher-priority original is conflict evidence, even if that original
    itself loses to an uploaded subtitle. This preserves the §4.5 whole-segment
    exclusion rule without fabricating partial utterances.
    """
    if not scenes or len({s.index for s in scenes}) != len(scenes):
        raise ValueError("expected nonempty unique scenes")
    if len({s.id for s in segments}) != len(segments):
        raise ValueError("duplicate segment ID")
    originals = tuple(sorted(segments, key=lambda s: (s.start, s.end, PRIORITY[s.source], s.id)))
    decisions = tuple(
        Decision(
            segment.id,
            not (
                conflicts := tuple(
                    other.id
                    for other in originals
                    if PRIORITY[other.source] < PRIORITY[segment.source]
                    and max(segment.start, other.start) < min(segment.end, other.end)
                )
            ),
            conflicts,
        )
        for segment in originals
    )
    selected = {d.segment_id for d in decisions if d.selected}
    links = tuple(
        (
            scene.index,
            tuple(
                (segment.id, overlap)
                for segment in originals
                if segment.id in selected
                and (overlap := min(scene.end, segment.end) - max(scene.start, segment.start)) > 0
            ),
        )
        for scene in sorted(scenes, key=lambda s: s.index)
    )
    return MappingResult(originals, decisions, links)
