"""모델 출력 검증 — JSON → schema → 어휘·근거 → 안전한 값.

이 파일이 티켓 제약의 대부분을 지키는 자리다.

- **부분 반영을 하지 않는다.** 하나라도 어긋나면 그 scene 의 출력을 통째로 거부한다.
  "형식이 잘못된 출력을 정상 데이터에 부분 적용하지 않는다"(`docs/frd.md:133`)를 필드
  단위로 지키는 방법은 골라 담지 않는 것뿐이다.
- **근거 없는 값을 통과시키지 않는다.** 근거 라벨이 없는 판단은 거부한다. 근거가 없을 때
  모델이 쓸 답은 `null`(caption·scene_type)과 `unknown`(shot_type)으로 이미 열려 있다.
- **어휘를 강제한다.** `shot_type` 은 `schema.py` 의 `Literal` 이, `scene_type` 은 설정의
  닫힌 어휘가 정본이다.
- **날짜 태그는 유형 자체가 없다.** `schema.TagCandidateType` 에서 빠져 있으므로 여기서
  따로 막을 것이 없다 — 모델이 내면 pydantic 이 먼저 거부한다.

**왜 강등이 아니라 거부인가.** `query_resolver/validator.py` 는 어긋난 항목을 강등하고
`AnchorFinding` 으로 기록한다. 검색 한 번은 빈 해석으로도 BM25 fallback 이 성립하기
때문이다(FRD §6.2). 이 단계는 반대다 — 결과가 `scene.caption`·`scene.shot_type` 이라는
**정본 컬럼**에 들어가고, 한 번 들어가면 그것이 그 장면의 설명이 된다. 근거가 의심스러운
값을 낮은 신뢰도로 저장하는 것과 저장하지 않는 것 사이에서, FRD F-04 의 "값만 저장하지
않고 출처와 확인 가능한 근거 위치를 연결한다" 는 후자를 요구한다.

거부의 대가는 단계 실패다. 계약 §9.2 의 `VLM_SCHEMA_INVALID` 는 **영구**이므로 재시도가
없고, scene 하나의 출력이 깨지면 그 클립의 VLM 단계 전체가 실패로 기록된다. 비치명
단계라 run 은 계속 가고 OCR·ASR 결과는 그대로 쓰인다(`docs/frd.md:137`). 이 판단의
대안(장면 단위로 버리고 나머지를 반납)은 `docs/vlm-metadata.md` 에 적어 둔다.
"""

import json
from collections.abc import Sequence
from typing import Final

from pydantic import ValidationError

from npick_worker import korean_tokens
from npick_worker.vlm_metadata.config import VlmMetadataConfig
from npick_worker.vlm_metadata.models import (
    CONFIDENCE_DECIMALS,
    Caption,
    KeyframeRef,
    SceneMetadata,
    ShotTypeJudgement,
    TagCandidate,
)
from npick_worker.vlm_metadata.prompt import labels_for
from npick_worker.vlm_metadata.schema import RawJudgement, RawSceneMetadata

#: 모듈 오류 코드. 잡 레이어가 계약 §9.2 의 같은 이름으로 번역한다.
VLM_SCHEMA_INVALID: Final[str] = "VLM_SCHEMA_INVALID"

#: 거부 사유에 실을 원문의 길이 상한. 전부 넣으면 로그가 모델 출력으로 뒤덮인다.
_RAW_EXCERPT_LIMIT: Final[int] = 400

#: 근거가 비어 있어도 되는 유일한 값.
_SHOT_TYPE_WITHOUT_EVIDENCE: Final[str] = "unknown"


class VlmSchemaInvalidError(ValueError):
    """출력이 계약과 다르다. **영구 실패다** — 같은 입력에 같은 출력이 다시 온다.

    `ValueError` 하위인 이유는 단계 구현이 잡 레이어를 임포트할 수 없기 때문이다
    (`ai/AGENTS.md`). 계약 §9.2 의 코드로 번역하는 일은 경계인 `jobs/registry.py` 가 한다.

    `raw_output` 은 **거부된 출력의 원문**이다. 티켓이 "평가에 사용한 설정 version 과 원시
    결과를 보존한다" 를 요구하는데, 가장 볼 가치가 있는 원문이 거부된 쪽이다 — 통과한
    출력만 남기면 프롬프트를 왜 고쳐야 하는지가 기록에서 사라진다. 운영 경로는 이 값을
    저장하지 않는다(담을 컬럼이 없다). 채우는 쪽은 `describer.describe_scene` 이고 쓰는
    쪽은 `report.py` 다.
    """

    code = VLM_SCHEMA_INVALID

    def __init__(self, message: str, raw_output: str | None = None) -> None:
        super().__init__(message)
        self.raw_output = raw_output


def parse_raw(payload: str) -> RawSceneMetadata:
    """1단계. 모양이 깨졌으면 복구하지 않는다.

    코드펜스를 벗기는 정도만 봐준다 — 프롬프트가 금지하지만 모델이 자주 붙이고, 이건
    의도가 명확해서 복구가 추측이 아니다(`query_resolver/validator.py` 와 같은 판단).
    그 밖의 어떤 교정도 하지 않는다. 잘린 JSON 을 이어 붙이거나 따옴표를 고치기 시작하면
    "무엇을 검증했는가" 가 사라진다.
    """
    text = _strip_code_fence(payload.strip())
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        msg = f"VLM 출력이 JSON 이 아니다: {exc} · 원문 {_excerpt(payload)}"
        raise VlmSchemaInvalidError(msg, payload) from exc
    if not isinstance(data, dict):
        msg = f"VLM 출력이 객체가 아니다: {type(data).__name__}"
        raise VlmSchemaInvalidError(msg, payload)
    try:
        return RawSceneMetadata.model_validate(data)
    except ValidationError as exc:
        msg = f"VLM 출력이 schema 와 맞지 않는다: {exc.error_count()}건 · {exc}"
        raise VlmSchemaInvalidError(msg, payload) from exc


def validate(
    raw: RawSceneMetadata,
    scene_index: int,
    keyframes: Sequence[KeyframeRef],
    cfg: VlmMetadataConfig,
) -> SceneMetadata:
    """2단계. 어휘·근거·상한을 검사하고 우리 어휘로 옮긴다.

    Args:
        raw: `parse_raw` 를 통과한 출력.
        scene_index: 어느 장면인가. **모델에게 묻지 않은 값이다** — 화면에 없으므로
            물으면 지어낸다(`schema.RawSceneMetadata` 주석).
        keyframes: 모델에 **실제로 넣은** 순서의 keyframe. 근거 라벨이 이 순서를 가리킨다.
        cfg: 어휘와 상한.

    Returns:
        검증된 scene metadata. `caption` 이 `None` 이거나 `tag_candidates` 가 비어 있을
        수 있고, 둘 다 정상이다.

    Raises:
        VlmSchemaInvalidError: 어휘·근거·상한 중 하나라도 어긋났다.
    """
    if not keyframes:
        # 상류가 keyframe 을 주지 않았는데 호출이 일어났다. 근거가 있을 수 없는 출력이라
        # 검증 자체가 성립하지 않는다. 이건 모델 잘못이 아니라 호출부 잘못이다.
        msg = f"근거로 쓸 keyframe 이 없다: scene_index={scene_index}"
        raise VlmSchemaInvalidError(msg)

    labels = labels_for(keyframes)

    return SceneMetadata(
        scene_index=scene_index,
        shot_type=_to_shot_type(raw, labels),
        caption=_to_caption(raw, labels, cfg),
        tag_candidates=_to_tag_candidates(raw, labels, cfg),
    )


def _to_caption(
    raw: RawSceneMetadata, labels: dict[str, KeyframeRef], cfg: VlmMetadataConfig
) -> Caption | None:
    if raw.caption is None:
        return None
    value = raw.caption.value.strip()
    if not value:
        msg = "caption 이 공백뿐이다. 근거가 없으면 null 이어야 한다"
        raise VlmSchemaInvalidError(msg)
    if len(value) > cfg.caption_max_chars:
        # 자르지 않는다. 잘린 문장은 "AI 가 만든 장면 설명" 이 아니라 그 일부이고,
        # 검색 대상 컬럼(`scene.caption`)에 들어가면 그 사실이 사라진다.
        msg = f"caption 이 {cfg.caption_max_chars}자를 넘는다: {len(value)}자"
        raise VlmSchemaInvalidError(msg)
    return Caption(
        value=value,
        # 색인 토큰을 워커가 만든다. BE 에 Kiwi 가 없고 색인과 질의가 같은 설정을 써야
        # 한다(`korean_tokens.py`). 빈 튜플이 정상일 수 있다 — 내용어가 없는 설명이다.
        tokens=korean_tokens.index_tokens(value),
        confidence=_round(raw.caption.confidence),
        evidence=_resolve_evidence(raw.caption, labels, path="caption", required=True),
    )


def _to_shot_type(raw: RawSceneMetadata, labels: dict[str, KeyframeRef]) -> ShotTypeJudgement:
    """`scene.shot_type` 은 `NOT NULL` 이라 이 값만 언제나 존재한다.

    `unknown` 일 때만 근거가 없어도 된다. "판단할 근거가 부족하다" 는 판단에 근거 프레임을
    요구하는 것은 뜻이 통하지 않고, 그렇다고 근거를 지어내게 하는 것이 그 대안이어서는
    안 된다.
    """
    required = raw.shot_type.value != _SHOT_TYPE_WITHOUT_EVIDENCE
    return ShotTypeJudgement(
        value=raw.shot_type.value,
        confidence=_round(raw.shot_type.confidence),
        evidence=_resolve_evidence(raw.shot_type, labels, path="shot_type", required=required),
    )


def _to_tag_candidates(
    raw: RawSceneMetadata, labels: dict[str, KeyframeRef], cfg: VlmMetadataConfig
) -> tuple[TagCandidate, ...]:
    """`scene_type` 을 태그 후보로 합치고 상한·중복을 정리한다.

    `scene_type` 이 전용 필드로 오고 결과에서는 태그 후보 하나가 되는 것이 이 단계의
    설계다(`models.py`). 그래서 **`tag_candidates` 안의 `scene_type` 은 거부한다** —
    프롬프트가 그 유형을 목록에서 빼고 전용 필드를 주므로 거기 들어온 값은 지시를 벗어난
    것이고, 받아 주면 같은 장면에 장면 유형 후보가 둘 생긴다.
    """
    candidates: list[TagCandidate] = []

    if raw.scene_type is not None:
        value = raw.scene_type.value.strip()
        if value not in cfg.scene_type_vocabulary:
            msg = f"scene_type 이 닫힌 어휘에 없다: {value!r}"
            raise VlmSchemaInvalidError(msg)
        candidates.append(
            TagCandidate(
                type="scene_type",
                value=value,
                confidence=_round(raw.scene_type.confidence),
                evidence=_resolve_evidence(
                    raw.scene_type, labels, path="scene_type", required=True
                ),
            )
        )

    if len(raw.tag_candidates) > cfg.max_tag_candidates_per_scene:
        msg = (
            f"태그 후보가 상한을 넘는다: {len(raw.tag_candidates)}개 "
            f"(상한 {cfg.max_tag_candidates_per_scene})"
        )
        raise VlmSchemaInvalidError(msg)

    for position, tag in enumerate(raw.tag_candidates):
        path = f"tag_candidates[{position}]"
        if tag.type == "scene_type":
            msg = f"{path} 에 scene_type 이 있다. 장면 유형은 전용 필드로 낸다"
            raise VlmSchemaInvalidError(msg)
        value = tag.value.strip()
        if not value:
            msg = f"{path} 의 value 가 공백뿐이다"
            raise VlmSchemaInvalidError(msg)
        candidates.append(
            TagCandidate(
                type=tag.type,
                value=value,
                confidence=_round(tag.confidence),
                evidence=_resolve_evidence(tag, labels, path=path, required=True),
            )
        )

    return _deduplicate(candidates)


def _deduplicate(candidates: Sequence[TagCandidate]) -> tuple[TagCandidate, ...]:
    """같은 `(type, value)` 를 하나로 합친다. 근거는 합집합, 신뢰도는 높은 쪽이다.

    거부하지 않고 합치는 이유는 잃는 것이 없기 때문이다. 값이 같으므로 무엇을 버릴지
    고르는 판단이 아니고, BE 쪽에는 `UNIQUE(tag_type, match_value)` 와
    `uq_tagging_scope_tag` 가 있어 중복을 그대로 보내면 저장에서 충돌한다.
    """
    merged: dict[tuple[str, str], TagCandidate] = {}
    for candidate in candidates:
        key = (candidate.type, candidate.value)
        existing = merged.get(key)
        if existing is None:
            merged[key] = candidate
            continue
        merged[key] = TagCandidate(
            type=candidate.type,
            value=candidate.value,
            confidence=max(existing.confidence, candidate.confidence),
            evidence=_unique(existing.evidence + candidate.evidence),
        )
    return tuple(merged.values())


def _resolve_evidence(
    judgement: RawJudgement,
    labels: dict[str, KeyframeRef],
    *,
    path: str,
    required: bool,
) -> tuple[KeyframeRef, ...]:
    """근거 라벨을 실제 keyframe 으로 되돌린다.

    모델이 주지 않은 라벨을 적으면 거부한다. 그 라벨이 가리키는 프레임이 없으므로
    `tag_evidence.source_ref_id` 를 채울 수 없고, 근거 없는 값이 근거가 있는 것처럼
    저장되는 경로가 바로 여기다.

    같은 라벨을 두 번 적은 것은 합친다 — 버리는 정보가 없다.
    """
    resolved: list[KeyframeRef] = []
    for label in judgement.evidence:
        keyframe = labels.get(label)
        if keyframe is None:
            msg = f"{path} 의 근거 라벨이 입력에 없다: {label!r} (있는 라벨 {sorted(labels)})"
            raise VlmSchemaInvalidError(msg)
        resolved.append(keyframe)
    unique = _unique(tuple(resolved))
    if required and not unique:
        msg = f"{path} 에 근거 keyframe 이 없다. 근거가 없으면 null 또는 unknown 이어야 한다"
        raise VlmSchemaInvalidError(msg)
    return unique


def _unique(keyframes: tuple[KeyframeRef, ...]) -> tuple[KeyframeRef, ...]:
    """순서를 지키며 중복을 없앤다."""
    seen: dict[KeyframeRef, None] = {}
    for keyframe in keyframes:
        seen.setdefault(keyframe, None)
    return tuple(seen)


def _round(confidence: float) -> float:
    """`tag_evidence.confidence` 가 `numeric(5,4)` 다. 보내기 전에 맞춘다."""
    return round(confidence, CONFIDENCE_DECIMALS)


def _strip_code_fence(text: str) -> str:
    """프롬프트가 금지한 코드펜스를 벗긴다. 그 밖의 교정은 하지 않는다."""
    if not text.startswith("```"):
        return text
    lines = text.splitlines()
    # 첫 줄은 ``` 또는 ```json. 마지막 ``` 줄까지 잘라낸다.
    body = lines[1:]
    while body and body[-1].strip() != "```":
        body.pop()
    if body:
        body.pop()
    return "\n".join(body).strip()


def _excerpt(payload: str) -> str:
    text = payload.strip()
    if len(text) <= _RAW_EXCERPT_LIMIT:
        return repr(text)
    return repr(text[:_RAW_EXCERPT_LIMIT] + "…")
