"""Deterministic preliminary transcript adoption and ASR-need judgement."""

from .config import TranscriptSelectionConfig, get_default_config, load_config
from .coverage import Range, uncovered_ranges
from .selector import PRIORITY, SELECTION_VERSION, Decision, Segment, Selection, Source, select

__all__ = [
    "PRIORITY",
    "SELECTION_VERSION",
    "Decision",
    "Range",
    "Segment",
    "Selection",
    "Source",
    "TranscriptSelectionConfig",
    "get_default_config",
    "load_config",
    "select",
    "uncovered_ranges",
]
