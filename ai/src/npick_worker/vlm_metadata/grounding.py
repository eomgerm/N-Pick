"""장면에 이미 연결된 텍스트를 모델 입력으로 구성한다. 추출·대사 선택·매핑은 하지 않는다."""

import json
from dataclasses import dataclass
from typing import Literal


@dataclass(frozen=True, slots=True)
class OcrRef:
    """기존 OCR output.observations의 위치와 원본 프레임 참조."""

    scene_index: int
    timestamp_ms: int
    storage_key: str
    observation_index: int


@dataclass(frozen=True, slots=True)
class TranscriptRef:
    """기존 transcript snapshot 안의 구간. scene 연결은 상류가 결정한다."""

    scene_index: int
    storage_key: str
    segment_id: str
    s: int
    e: int
    source_detail: Literal["uploaded", "embedded", "asr"]


@dataclass(frozen=True, slots=True)
class OcrText:
    ref: OcrRef
    raw_text: str
    text_key: str
    confidence: float


@dataclass(frozen=True, slots=True)
class TranscriptText:
    ref: TranscriptRef
    t: str


@dataclass(frozen=True, slots=True)
class Grounding:
    # 라벨 하나가 묶인 OCR의 원본 관측 여러 개를 참조할 수 있다.
    references: tuple[tuple[str, tuple[OcrRef | TranscriptRef, ...]], ...] = ()
    text: str = ""


def prepare_grounding(
    scene_index: int,
    ocr: tuple[OcrText, ...],
    transcripts: tuple[TranscriptText, ...],
    *,
    max_ocr_chars: int,
    max_transcript_chars: int,
) -> Grounding:
    """원문을 자르지 않고 항목 단위로 제한한다. 0은 제한 없음(실측 전 기본값)."""
    if any(item.ref.scene_index != scene_index for item in ocr) or any(
        item.ref.scene_index != scene_index for item in transcripts
    ):
        raise ValueError("다른 장면의 grounding 입력이 섞였다")
    groups: dict[tuple[str, str], list[OcrText]] = {}
    for item in sorted(ocr, key=lambda item: (item.ref.timestamp_ms, item.ref.observation_index)):
        # textKey가 같더라도 원문이 다르면 합치지 않는다.
        groups.setdefault((item.text_key, item.raw_text), []).append(item)
    refs: list[tuple[str, tuple[OcrRef | TranscriptRef, ...]]] = []
    ocr_rows: list[dict[str, object]] = []
    used = 0
    for group in groups.values():
        item = group[0]
        if max_ocr_chars and used + len(item.raw_text) > max_ocr_chars:
            continue
        label = f"ocr_{len(ocr_rows) + 1}"
        refs.append((label, tuple(entry.ref for entry in group)))
        ocr_rows.append(
            {
                "label": label,
                "rawText": item.raw_text,
                "timestampMs": [entry.ref.timestamp_ms for entry in group],
                "confidence": [entry.confidence for entry in group],
            }
        )
        used += len(item.raw_text)
    transcript_rows: list[dict[str, object]] = []
    used = 0
    for segment in sorted(
        transcripts, key=lambda segment: (segment.ref.s, segment.ref.e, segment.ref.segment_id)
    ):
        if max_transcript_chars and used + len(segment.t) > max_transcript_chars:
            continue
        label = f"tr_{len(transcript_rows) + 1}"
        refs.append((label, (segment.ref,)))
        transcript_rows.append(
            {
                "label": label,
                "s": segment.ref.s,
                "e": segment.ref.e,
                "t": segment.t,
                "sourceDetail": segment.ref.source_detail,
            }
        )
        used += len(segment.t)
    return Grounding(
        references=tuple(refs),
        text=json.dumps({"ocr": ocr_rows, "transcript": transcript_rows}, ensure_ascii=False),
    )
