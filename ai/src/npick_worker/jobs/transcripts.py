"""Approved transcript artifact boundary; selection and ASR remain stage implementations."""

from collections.abc import Mapping
from typing import Any, Final, Literal

from pydantic import Field, StrictBool, StrictInt, model_validator

from npick_worker.jobs.errors import UpstreamOutputInvalidError
from npick_worker.jobs.models import ArtifactRef, WireResponse
from npick_worker.jobs.versions import WireModel


class TranscriptSegment(WireModel):
    segment_id: str = Field(min_length=1)
    s: StrictInt = Field(ge=0)
    e: StrictInt = Field(gt=0)
    t: str = Field(min_length=1)
    source_detail: Literal["uploaded", "embedded", "asr"]

    @model_validator(mode="after")
    def interval(self) -> "TranscriptSegment":
        if self.e <= self.s or not self.t.strip():
            raise ValueError("invalid transcript interval or text")
        return self


class TranscriptSegments(WireModel):
    schema_version: Literal["npick.transcript.segments/v1"]
    segments: list[TranscriptSegment]

    @model_validator(mode="after")
    def unique_ids(self) -> "TranscriptSegments":
        if len({s.segment_id for s in self.segments}) != len(self.segments):
            raise ValueError("duplicate segmentId")
        return self


class TranscriptDecision(WireModel):
    segment_id: str
    selected: StrictBool
    reason_code: Literal["PREFERRED_SUBTITLE", "ASR_SUPPLEMENT", "OVERLAPS_HIGHER_PRIORITY"]
    conflicts_with: list[str]


class TranscriptDecisions(WireModel):
    schema_version: Literal["npick.transcript.decisions/v1"]
    segments_artifact: ArtifactRef
    decisions: list[TranscriptDecision]


class TranscriptSnapshot(WireModel):
    """원본과 최종 채택 결과의 기존 artifact 참조."""

    segments_artifact: ArtifactRef
    decisions_artifact: ArtifactRef

    @model_validator(mode="after")
    def artifact_kinds(self) -> "TranscriptSnapshot":
        if self.segments_artifact.kind != "transcript_segments":
            raise ValueError("invalid segments artifact kind")
        if self.decisions_artifact.kind != "transcript_decisions":
            raise ValueError("invalid decisions artifact kind")
        if self.segments_artifact.storage_key == self.decisions_artifact.storage_key:
            raise ValueError("snapshot artifacts must have distinct storage keys")
        return self


class MappedSegment(WireModel):
    segment_id: str = Field(min_length=1)
    overlap_ms: StrictInt = Field(gt=0)


class SceneTranscriptLinks(WireModel):
    scene_index: StrictInt = Field(ge=0)
    segments: list[MappedSegment]
    #: Kiwi 색인 토큰을 공백으로 이은 것. 곧 `scene.transcript_tokens` 가 된다.
    #: **BE 가 만들 수 없는 값이라 여기 있다** — 형태소 분석은 파이프라인의 일이고
    #: (`docs/architecture/02-container.md`), 색인과 질의가 같은 설정을 써야 한다.
    #: 그래서 `versions.detail.tokenizer` 가 그 설정의 식별자를 함께 싣는다
    #: (`ocr`·`vlm_metadata` 와 같은 규약). 빈 문자열이 정상이다 — 조사·감탄사뿐인
    #: 대사는 원문이 있어도 내용어가 없고, 대사 없는 장면은 연결 자체가 비어 있다.
    tokens: str

    @model_validator(mode="after")
    def unique_links(self) -> "SceneTranscriptLinks":
        ids = [segment.segment_id for segment in self.segments]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate segment in scene")
        return self


class SceneTranscriptMappingOutput(WireModel):
    """장면 연결 결과 계약. 선택·겹침 계산은 생산 단계가 담당한다."""

    transcript: TranscriptSnapshot
    scenes: list[SceneTranscriptLinks] = Field(min_length=1)

    @model_validator(mode="after")
    def unique_scenes(self) -> "SceneTranscriptMappingOutput":
        ids = [scene.scene_index for scene in self.scenes]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate scene in transcript mapping")
        return self


class UpstreamTranscriptArtifact(ArtifactRef):
    model_config = WireResponse.model_config


class UpstreamTranscriptSnapshot(WireResponse):
    segments_artifact: UpstreamTranscriptArtifact
    decisions_artifact: UpstreamTranscriptArtifact


class UpstreamMappedSegment(WireResponse):
    segment_id: str
    overlap_ms: StrictInt


class UpstreamSceneTranscriptLinks(WireResponse):
    scene_index: StrictInt
    segments: list[UpstreamMappedSegment]
    #: 기본값을 두는 이유는 `UpstreamSceneCaption.tokens` 와 같다 — 이 모델을 쓰는
    #: 하류가 토큰을 읽지 않아도 모양이 깨지지 않아야 한다.
    tokens: str = ""


class UpstreamSceneTranscriptMapping(WireResponse):
    """상류 추가 필드를 제거한 뒤 기존 출력 계약으로 값과 참조를 검증한다."""

    transcript: UpstreamTranscriptSnapshot
    scenes: list[UpstreamSceneTranscriptLinks]


def parse_scene_transcript_mapping(value: Any) -> SceneTranscriptMappingOutput:
    received = UpstreamSceneTranscriptMapping.model_validate(value)
    # dict 재검증으로 ArtifactRef 정본 타입을 복원해 snapshot 동등성 검사를 유지한다.
    return SceneTranscriptMappingOutput.model_validate(received.model_dump(by_alias=True))


def validate_snapshot(
    segments_ref: ArtifactRef,
    segments: TranscriptSegments,
    decisions: TranscriptDecisions,
) -> None:
    """Validate references and reported decisions without running a selection algorithm."""
    if decisions.segments_artifact != segments_ref:
        raise ValueError("decisions refer to a different snapshot")
    originals = {s.segment_id: s for s in segments.segments}
    ids = [d.segment_id for d in decisions.decisions]
    if len(set(ids)) != len(ids) or set(ids) != set(originals):
        raise ValueError("each original requires exactly one decision")
    priority = {"uploaded": 0, "embedded": 1, "asr": 2}
    for decision in decisions.decisions:
        segment = originals[decision.segment_id]
        if len(set(decision.conflicts_with)) != len(decision.conflicts_with):
            raise ValueError("duplicate conflict")
        if decision.selected:
            expected = "ASR_SUPPLEMENT" if segment.source_detail == "asr" else "PREFERRED_SUBTITLE"
            if decision.reason_code != expected or decision.conflicts_with:
                raise ValueError("selected decision has invalid reason or conflicts")
        elif decision.reason_code != "OVERLAPS_HIGHER_PRIORITY" or not decision.conflicts_with:
            raise ValueError("excluded decision requires overlap evidence")
        for conflict_id in decision.conflicts_with:
            conflict = originals.get(conflict_id)
            if (
                conflict is None
                or priority[conflict.source_detail] >= priority[segment.source_detail]
                or max(segment.s, conflict.s) >= min(segment.e, conflict.e)
            ):
                raise ValueError("invalid higher-priority overlap reference")


#: 매핑 결과의 상류 키. BE 의 단계 키는 snake_case 지만 계약 예시 다수가 camelCase 라
#: 두 표기를 모두 본다. **없는 키는 오류가 아니라 "매핑을 돌리지 않았다" 로 흐르므로**
#: (`resolve_mapping` 이 None 을 준다) 표기가 어긋나면 하류가 실패 없이 대사 0건으로
#: 돈다 — 조용한 결함이라 여기서 막는다.
_MAPPING_KEYS: Final = ("scene_transcript_mapping", "sceneTranscriptMapping")


def mapping_payload(upstream: Mapping[str, Any]) -> Any | None:
    """`scene_transcript_mapping` 단계 결과. 두 표기를 모두 받고 없으면 None 이다."""
    for key in _MAPPING_KEYS:
        if key in upstream:
            return upstream[key]
    return None


def transcript_refs(
    upstream: Mapping[str, Any], *, stage: str | None = None
) -> tuple[ArtifactRef, ...]:
    # 상위 transcript 별칭이 이전 snapshot이어도 최종 매핑의 참조가 정본이다.
    # `text_embedding` 도 같은 이유로 매핑 쪽을 본다 — 별칭이 가리키는 옛 snapshot 을
    # 받아 오면 `resolve_mapping` 이 문서를 찾지 못해 이 단계가 통째로 죽는다.
    payload = mapping_payload(upstream)
    if stage in {"vlm_metadata", "text_embedding"} and payload is not None:
        mapping = parse_scene_transcript_mapping(payload)
        return (mapping.transcript.segments_artifact, mapping.transcript.decisions_artifact)
    transcript = upstream.get("transcript")
    if transcript is None:
        return ()
    if not isinstance(transcript, dict) or "segmentsArtifact" not in transcript:
        raise ValueError("transcript requires segmentsArtifact")
    refs = [ArtifactRef.model_validate(transcript["segmentsArtifact"])]
    if refs[0].kind != "transcript_segments":
        raise ValueError("invalid segments artifact kind")
    if "decisionsArtifact" in transcript:
        refs.append(ArtifactRef.model_validate(transcript["decisionsArtifact"]))
        if refs[1].kind != "transcript_decisions":
            raise ValueError("invalid decisions artifact kind")
    return tuple(refs)


def resolve_mapping(
    upstream: Mapping[str, Any], documents: Mapping[str, Mapping[str, Any]]
) -> tuple[SceneTranscriptMappingOutput, dict[str, TranscriptSegment]] | None:
    """장면 연결 결과와 그 연결이 가리키는 채택 세그먼트.

    `scene_transcript_mapping` 이 없으면 `None` 이다 — 비치명 상류라 정상 입력이고,
    그때 하류는 대사 없이 돈다.

    연결이 **채택되지 않은** 세그먼트를 가리키거나 원본보다 긴 겹침을 주장하면 거절한다.
    그 값을 받아들이면 선택 단계가 버린 자막이 캡션·벡터·근거로 되살아난다.

    Returns:
        `(매핑, segmentId → 원본 세그먼트)`. 사전에는 **연결된 것만** 들어간다.
    """
    payload = mapping_payload(upstream)
    if payload is None:
        return None
    try:
        mapping = parse_scene_transcript_mapping(payload)
        snapshot = mapping.transcript
        segments = TranscriptSegments.model_validate(
            documents[snapshot.segments_artifact.storage_key]
        )
        decisions = TranscriptDecisions.model_validate(
            documents[snapshot.decisions_artifact.storage_key]
        )
        validate_snapshot(snapshot.segments_artifact, segments, decisions)
        selected = {decision.segment_id for decision in decisions.decisions if decision.selected}
        originals = {segment.segment_id: segment for segment in segments.segments}
        linked: dict[str, TranscriptSegment] = {}
        for scene in mapping.scenes:
            for link in scene.segments:
                if link.segment_id not in selected:
                    raise ValueError("mapped segment must be selected")
                segment = originals[link.segment_id]
                if link.overlap_ms > segment.e - segment.s:
                    raise ValueError("overlap exceeds original segment duration")
                linked[link.segment_id] = segment
        return mapping, linked
    except (ValueError, KeyError, TypeError) as exc:
        raise UpstreamOutputInvalidError("invalid scene transcript mapping or snapshot") from exc
