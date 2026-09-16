"""모델이 "없다" 고 말한 표기를 schema 의 '없음' 으로 모은다. 그 밖의 교정은 하지 않는다.

`validator.parse_output` 의 0단계다. JSON 을 읽은 뒤 `RawSceneMetadata` 로 넘기기 전에
아래 표의 자리만 바꾸고, 바꾼 자리를 전부 돌려준다.

| 모델이 낸 것 | 옮기는 값 | 왜 |
| --- | --- | --- |
| `caption.value` 가 `null`·`"없음"` 류 | `caption: null` | 설명이 없다는 답이 계약에 이미 있다 |
| `scene_type.value` 가 같은 값 | `scene_type: null` | 같다. 어휘 밖이라고 날릴 자리가 아니다 |
| `shot_type.value` 가 같은 값 | `"unknown"` | 그 자리는 `NOT NULL` 이고 '없음' 이 어휘에 있다 |
| `tag_candidates: null` | `[]` | 후보가 없다는 답이 빈 배열이다 |
| 판단의 `evidence: null` | `[]` | 근거가 없다는 답이 빈 배열이다. 그 뒤는 `validator.py` 다 |

**이것은 보정이 아니다.** 티켓 제약은 "검증 실패 출력을 고쳐서 넣는 자동 보정 금지" 이고,
여기서 하는 일은 실패한 출력을 고치는 것이 아니라 **같은 뜻의 두 표기를 하나로 모으는
것**이다. 없는 값을 채우지 않고, 있는 값을 다른 값으로 바꾸지 않고, 잘린 문자열을 잇지
않는다. 프롬프트가 이미 "정보가 없으면 caption·scene_type 은 null, shot_type 은 unknown,
tag_candidates 는 빈 배열" 이라고 말해 두었으므로(`config/vlm_metadata.v2.toml`), 모델이
`"없음"` 이라고 쓴 것은 다른 답이 아니라 그 답의 다른 표기다.

**바꾼 자리를 세어 보고한다.** 조용히 받아 주면 "프롬프트를 고쳤는데 출력이 그대로" 를
못 잡는다는 지적(`schema._Raw` 의 `extra="forbid"`)이 여기에도 그대로 걸린다. 그래서
`normalize` 는 바꾼 경로를 돌려주고, `describer` 가 그것을 `SceneDescription` 에 싣고,
잡 레이어가 `normalizedValues` metric 으로 올린다 — 비율이 튀면 프롬프트를 봐야 한다는
신호다(`unknownShotTypes` 와 같은 용도).

**무엇을 정규화하지 않는가.** 경계를 여기 적어 둔다. 넓히면 그 순간 보정이 된다.

- **자리 자체가 없는 것**(`shot_type` 키가 없거나 `shot_type: null`) — 거부한다. 그 자리를
  만들려면 `confidence` 를 지어내야 하고, 지어낸 신뢰도는 `tag_evidence.confidence` 가 된다.
  정규화는 **값 자리에 들어온 '없음' 표기**만 옮긴다.
- **공백뿐인 문자열** — 거부한다(`validator._to_caption`). "없음" 이라고 답한 것과 아무것도
  쓰지 못한 것은 다른 사실이고, 뒤엣것은 출력이 깨졌다는 신호다.
- **`tag_candidates[i].value` 의 '없음' 표기** — 거부한다(`validator._to_tag_candidates`).
  태그는 없으면 안 붙는 것이라 그 자리의 '없음' 은 빈 배열이지 값이 아니다. 항목을 조용히
  빼면 그게 부분 적용이다(`docs/frd.md` F-03).
- **`confidence` 의 `null`** — 거부한다. 숫자 자리의 '없음' 은 0 이 아니다.

**이 목록이 설정이 아니라 코드에 있는 이유.** `scene_type_vocabulary` 와 다르다. 저쪽은
모델에게 **요구하는** 어휘라 바뀌면 프롬프트가 바뀌지만, 이 목록은 모델에게 말한 적 없는
**받아 읽는 규칙**이다. 설정에 두면 값 하나를 더할 때마다 `config_version` 과
`stage_version` 이 움직이는데, 프롬프트도 요청도 그대로다 — 재현 기록이 거짓말을 하게 된다.
`EVIDENCE_LABEL_PATTERN` 이 코드에 있는 것과 같은 자리다.
"""

import unicodedata
from collections.abc import Mapping
from typing import Any, Final

#: 값 자리에서 "없음" 을 뜻하는 표기. `_fold` 를 거친 형태로 적는다.
#:
#: 빈 문자열은 **여기 없다.** 그건 답이 아니라 깨진 출력이고 거부가 정답이다(모듈 docstring).
NULL_EQUIVALENTS: Final[frozenset[str]] = frozenset(
    {
        "null",
        "none",
        "nil",
        "n/a",
        "na",
        "unknown",
        "undefined",
        "unspecified",
        "없음",
        "알수없음",
        "모름",
        "미상",
        "불명",
        "해당없음",
        "확인불가",
        "-",
        "--",
    }
)

#: `shot_type` 의 '없음'. `validator._SHOT_TYPE_WITHOUT_EVIDENCE` 와 같은 값이고, 그쪽은
#: "근거를 비워도 되는 값" 이라는 다른 사실을 말한다. 뜻이 다르므로 상수도 나눈다.
_SHOT_TYPE_ABSENT: Final[str] = "unknown"

#: 값 자리가 '없음' 이면 통째로 `null` 이 되는 판단들. 둘 다 계약에 `null` 이 열려 있다.
_NULLABLE_JUDGEMENTS: Final[tuple[str, ...]] = ("caption", "scene_type")


def means_absent(value: object) -> bool:
    """이 값이 "없다" 는 답인가.

    문자열만 본다. `0` 이나 `False` 는 값이 있는 것이고, 숫자 자리의 '없음' 을 0 으로
    읽는 것이 정확히 이 파일이 하지 않기로 한 일이다.
    """
    if value is None:
        return True
    if not isinstance(value, str):
        return False
    return _fold(value) in NULL_EQUIVALENTS


def normalize(data: Mapping[str, Any]) -> tuple[dict[str, Any], tuple[str, ...]]:
    """'없음' 표기를 schema 의 '없음' 으로 모은다.

    Args:
        data: `json.loads` 가 돌려준 객체. 아직 schema 를 통과하지 않았다.

    Returns:
        `(정규화한 사본, 바꾼 자리의 경로)`. 원본은 고치지 않는다 — 거부된 출력의 원문을
        보존하는 경로(`VlmSchemaInvalidError.raw_output`)와 같은 이유다.
    """
    normalized = dict(data)
    changed: list[str] = []

    for field in _NULLABLE_JUDGEMENTS:
        judgement = normalized.get(field)
        if not isinstance(judgement, dict) or "value" not in judgement:
            continue
        if means_absent(judgement["value"]):
            normalized[field] = None
            changed.append(field)

    shot_type = normalized.get("shot_type")
    if isinstance(shot_type, dict) and "value" in shot_type:
        # `unknown` 자신이 '없음' 표기 목록에 있다. 이미 계약의 값인 것을 바꿨다고 적으면
        # metric 이 "모델이 계약과 다르게 답했다" 를 세는 값이 아니게 된다.
        value = shot_type["value"]
        if value != _SHOT_TYPE_ABSENT and means_absent(value):
            normalized["shot_type"] = {**shot_type, "value": _SHOT_TYPE_ABSENT}
            changed.append("shot_type.value")

    if "tag_candidates" in normalized and normalized["tag_candidates"] is None:
        normalized["tag_candidates"] = []
        changed.append("tag_candidates")

    for field in ("caption", "shot_type", "scene_type"):
        replaced = _without_null_evidence(normalized.get(field))
        if replaced is not None:
            normalized[field] = replaced
            changed.append(f"{field}.evidence")

    candidates = normalized.get("tag_candidates")
    if isinstance(candidates, list):
        items = list(candidates)
        for position, candidate in enumerate(items):
            replaced = _without_null_evidence(candidate)
            if replaced is not None:
                items[position] = replaced
                changed.append(f"tag_candidates[{position}].evidence")
        normalized["tag_candidates"] = items

    return normalized, tuple(changed)


def _without_null_evidence(judgement: object) -> dict[str, Any] | None:
    """`evidence: null` 을 빈 배열로 옮긴 사본. 바꿀 것이 없으면 `None` 이다."""
    if not isinstance(judgement, dict) or judgement.get("evidence", ()) is not None:
        return None
    return {**judgement, "evidence": []}


def _fold(text: str) -> str:
    """비교용 형태 — NFKC + 공백 제거 + casefold.

    공백을 지우는 이유는 "알 수 없음" 과 "알수없음" 이 같은 답이기 때문이다. `ocr/merge.py`
    의 `comparison_text` 와 같은 규칙이지만 **그것을 임포트하지 않는다** — 두 단계는 서로의
    상류가 아니고, 의존을 걸면 VLM 만 도는 파드가 `ocr` 을 통해 rapidocr·onnxruntime 을
    끌어온다(`models.KeyframeRef` 의 같은 판단).
    """
    return "".join(unicodedata.normalize("NFKC", text).casefold().split())
