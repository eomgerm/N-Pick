"""모델이 "없다" 고 말한 표기를 schema 의 '없음' 으로 모은다. 그 밖의 교정은 하지 않는다.

`validator.parse_output` 의 0단계다. JSON 을 읽은 뒤 `RawSceneMetadata` 로 넘기기 전에
아래 표의 자리만 바꾸고, 바꾼 자리를 전부 돌려준다.

| 모델이 낸 것 | 옮기는 값 | 갈래 | 왜 |
| --- | --- | --- | --- |
| `caption.value` 가 `"없음"` 류 문자열 | `caption: null` | 표기 | 없다는 답이 계약에 있다 |
| `caption.value` 가 `null` | `caption: null` | 모양 | 계약의 값을 한 칸 안쪽에 썼다 |
| `scene_type.value` 가 같은 값 | `scene_type: null` | 같다 | 어휘 밖이라고 날릴 자리가 아니다 |
| `shot_type.value` 가 같은 값 | `"unknown"` | 같다 | `NOT NULL` 이고 '없음' 이 어휘에 있다 |
| `tag_candidates: null` | `[]` | 모양 | 후보가 없다는 답이 빈 배열이다 |
| 판단의 `evidence: null` | `[]` | 모양 | 근거가 없다는 답이 빈 배열이다 |

**갈래를 나누어 센다 — 둘이 뜻하는 바가 반대다.**

- **표기**: 값 자리에 `"없음"`·`"N/A"` 처럼 **문자열로** "없다" 고 썼다. 계약에 없는 어휘이므로
  프롬프트를 사람이 봐야 한다는 신호다. metric `normalizedValues` 가 세는 것은 이쪽뿐이다.
- **모양**: 계약의 `null` 을 쓰되 자리가 한 칸 다르다. 모델은 계약의 값으로 답했고, 읽을 것이
  없는 장면(암전·전환)이면 **정상적으로 나온다**. metric `reshapedValues` 로 따로 센다.

**실측에서 걸린 것은 전부 '모양' 이었다**(2026-09-16, `docs/vlm-metadata.md` §6). 빈 화면을
넣어 재 보니 선정 모델은 판단 객체를 유지한 채 값만 비웠다 — `{"value": null, "confidence": 0,
"evidence": []}`. 한국어 표기는 한 번도 나오지 않았다. 둘을 합쳐 세면 전환·암전이 섞인 클립마다
`normalizedValues` 가 상시 0 이 아니게 되고, 그 숫자를 "프롬프트를 봐야 한다" 로 읽기로 한
운영자에게는 매 런이 오탐이 된다 — `shot_type` 의 `unknown` 예외와 같은 이유다(아래 코드).
그렇다고 한국어 표기를 목록에서 빼지는 않는다. 프롬프트도 모델도 바뀌면 다시 나올 수 있는
표기고, 목록에 있어서 치르는 비용이 없다.

**이것은 보정이 아니다.** 티켓 제약은 "검증 실패 출력을 고쳐서 넣는 자동 보정 금지" 이고,
여기서 하는 일은 실패한 출력을 고치는 것이 아니라 **같은 뜻의 두 표기를 하나로 모으는
것**이다. 없는 값을 채우지 않고, 있는 값을 다른 값으로 바꾸지 않고, 잘린 문자열을 잇지
않는다. 프롬프트가 이미 "정보가 없으면 caption·scene_type 은 null, shot_type 은 unknown,
tag_candidates 는 빈 배열" 이라고 말해 두었으므로(`config/vlm_metadata.v2.toml`), 모델이
`"없음"` 이라고 쓴 것은 다른 답이 아니라 그 답의 다른 표기다.

**바꾼 자리를 세어 보고한다.** 조용히 받아 주면 "프롬프트를 고쳤는데 출력이 그대로" 를
못 잡는다는 지적(`schema._Raw` 의 `extra="forbid"`)이 여기에도 그대로 걸린다. 그래서
`normalize` 는 바꾼 경로를 돌려주고, `describer` 가 그것을 `SceneDescription` 에 싣고,
잡 레이어가 `normalizedValues`·`reshapedValues` metric 으로 올린다 — 앞엣것은 0 이 아니면
프롬프트를 봐야 한다는 신호고(`unknownShotTypes` 와 같은 용도), 뒤엣것은 장면 내용에 따라
오르내리는 값이라 급등만 신호다.

**기록에는 둘을 합쳐 남긴다.** 실측 기록의 `normalizations` 는 바꾼 자리 전부이고, 그중
표기였던 자리를 `notations` 가 따로 적는다. 기록은 일어난 일을 빠뜨리지 않는 쪽이 맞고,
세는 방식은 읽는 쪽에서 고르면 된다.

**무엇을 정규화하지 않는가.** 경계를 여기 적어 둔다. 넓히면 그 순간 보정이 된다.

- **자리 자체가 없는 것**(`shot_type` 키가 없거나 `shot_type: null`) — 거부한다. 그 자리를
  만들려면 `confidence` 를 지어내야 하고, 지어낸 신뢰도는 `tag_evidence.confidence` 가 된다.
  정규화는 **값 자리에 들어온 '없음' 표기**만 옮긴다.
- **공백뿐인 문자열** — 거부한다(`validator._to_caption`). "없음" 이라고 답한 것과 아무것도
  쓰지 못한 것은 다른 사실이고, 뒤엣것은 출력이 깨졌다는 신호다.
- **`tag_candidates[i].value` 의 '없음' 표기** — 거부한다(`validator._to_tag_candidates`).
  태그는 없으면 안 붙는 것이라 그 자리의 '없음' 은 빈 배열이지 값이 아니다. 항목을 조용히
  빼면 그게 부분 적용이다(`docs/frd.md` F-03). 다만 거기서 보는 목록은 `NULL_EQUIVALENTS`
  가 아니라 **`EXPLICIT_ABSENT`** 다 — 태그 자리에는 진짜 값이 오므로, `"NA"`·`"-"` 를
  '없음' 으로 읽으면 멀쩡한 후보 하나 때문에 클립이 통째로 실패한다.
- **`confidence` 의 `null`** — 거부한다. 숫자 자리의 '없음' 은 0 이 아니다.

**이 목록이 설정이 아니라 코드에 있는 이유.** `scene_type_vocabulary` 와 다르다. 저쪽은
모델에게 **요구하는** 어휘라 바뀌면 프롬프트가 바뀌지만, 이 목록은 모델에게 말한 적 없는
**받아 읽는 규칙**이다. 설정에 두면 값 하나를 더할 때마다 `config_version` 과
`stage_version` 이 움직이는데, 프롬프트도 요청도 그대로다 — 재현 기록이 거짓말을 하게 된다.
`EVIDENCE_LABEL_PATTERN` 이 코드에 있는 것과 같은 자리다.
"""

import unicodedata
from collections.abc import Mapping
from typing import Any, Final, NamedTuple

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

#: **"없다" 는 말 자체**인 표기. `NULL_EQUIVALENTS` 의 부분집합이고 훨씬 좁다.
#:
#: 값이 올 수 있는 자리에서 값을 거부할 때 쓴다(`validator._to_tag_candidates`). 거기서
#: 넓은 목록을 쓰면 `"NA"`·`"-"` 같은 **진짜 값**이 거부되고, 태그 후보 하나의 거부는 장면
#: 거부이자 단계 전체 실패다(`docs/contracts/job-api.md` §9.2 상 영구). 조직 약칭 `NA`,
#: 화면에서 읽은 구분자 `-`, 짧은 영단어 `nil`·`unknown` 은 방송 자막에서 실제로 나온다 —
#: 그것을 '없음' 으로 읽는 것은 추측이고, 추측이 틀리면 클립 하나가 통째로 실패한다.
#: 반대로 여기 남긴 표기는 값으로 읽힐 여지가 없다.
EXPLICIT_ABSENT: Final[frozenset[str]] = frozenset(
    {
        "null",
        "none",
        "undefined",
        "unspecified",
        "없음",
        "알수없음",
        "모름",
        "미상",
        "불명",
        "해당없음",
        "확인불가",
    }
)


class Normalized(NamedTuple):
    """`normalize` 의 결과. **바꾼 자리를 두 갈래로 나누어** 돌려준다.

    나누는 이유는 둘이 뜻하는 바가 정반대이기 때문이다(2026-09-16 빈 화면 실측).
    합쳐 세면 `normalizedValues` 가 "모델이 계약과 다르게 답했다" 를 세는 값이 아니게 되고,
    그 숫자를 신호로 쓰기로 한 운영자에게는 매 런이 오탐이 된다.
    """

    #: 정규화한 사본.
    data: dict[str, Any]
    #: 바꾼 자리 **전부**. 실측 기록에 그대로 남는다 — 일어난 일을 빠뜨리지 않는다.
    changed: tuple[str, ...]
    #: 그중 값 자리에 **문자열 표기**가 들어와 바꾼 자리. `changed` 의 부분집합이고,
    #: metric `normalizedValues` 가 세는 것은 이쪽뿐이다.
    notations: tuple[str, ...]


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


def declares_absent(value: object) -> bool:
    """이 값이 **"없다" 는 말 자체**인가. `means_absent` 보다 좁다(`EXPLICIT_ABSENT`).

    `null` 을 참으로 읽지 않는 것도 다르다. 이쪽은 "빈 자리" 가 아니라 "없다고 적힌 글자" 를
    찾는 물음이라서다.
    """
    return isinstance(value, str) and _fold(value) in EXPLICIT_ABSENT


def normalize(data: Mapping[str, Any]) -> Normalized:
    """'없음' 표기를 schema 의 '없음' 으로 모은다.

    Args:
        data: `json.loads` 가 돌려준 객체. 아직 schema 를 통과하지 않았다.

    Returns:
        `Normalized`. 원본은 고치지 않는다 — 거부된 출력의 원문을 보존하는 경로
        (`VlmSchemaInvalidError.raw_output`)와 같은 이유다.

    **`changed` 의 내용과 순서는 규칙이 바뀌지 않는 한 그대로 둔다.** 실측 기록의
    `normalizations` 가 이 값이고, 재생 테스트가 그것을 지금 코드의 출력과 맞춰 본다
    (`tests/test_vlm_real_outputs.py`). 자리를 세는 방식만 달라진 것을 규칙이 바뀐 것처럼
    보이게 하면 GPU 서버의 기록이 회귀로 읽힌다.
    """
    normalized = dict(data)
    changed: list[str] = []
    notations: list[str] = []

    def record(path: str, *, notation: bool) -> None:
        """바꾼 자리를 적는다. `notation` 은 "문자열 표기였나" 다 — `Normalized` 참고."""
        changed.append(path)
        if notation:
            notations.append(path)

    for field in _NULLABLE_JUDGEMENTS:
        judgement = normalized.get(field)
        if not isinstance(judgement, dict) or "value" not in judgement:
            continue
        value = judgement["value"]
        if means_absent(value):
            normalized[field] = None
            # `{"value": null}` 은 계약의 값을 한 칸 안쪽에 쓴 것이라 표기가 아니다.
            record(field, notation=isinstance(value, str))

    shot_type = normalized.get("shot_type")
    if isinstance(shot_type, dict) and "value" in shot_type:
        # `unknown` 자신이 '없음' 표기 목록에 있다. 이미 계약의 값인 것을 바꿨다고 적으면
        # metric 이 "모델이 계약과 다르게 답했다" 를 세는 값이 아니게 된다.
        value = shot_type["value"]
        if value != _SHOT_TYPE_ABSENT and means_absent(value):
            normalized["shot_type"] = {**shot_type, "value": _SHOT_TYPE_ABSENT}
            record("shot_type.value", notation=isinstance(value, str))

    if "tag_candidates" in normalized and normalized["tag_candidates"] is None:
        normalized["tag_candidates"] = []
        record("tag_candidates", notation=False)

    for field in ("caption", "shot_type", "scene_type"):
        replaced = _without_null_evidence(normalized.get(field))
        if replaced is not None:
            normalized[field] = replaced
            record(f"{field}.evidence", notation=False)

    candidates = normalized.get("tag_candidates")
    if isinstance(candidates, list):
        items = list(candidates)
        for position, candidate in enumerate(items):
            replaced = _without_null_evidence(candidate)
            if replaced is not None:
                items[position] = replaced
                record(f"tag_candidates[{position}].evidence", notation=False)
        normalized["tag_candidates"] = items

    return Normalized(normalized, tuple(changed), tuple(notations))


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
