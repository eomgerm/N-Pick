"""Query Resolver 채점 지표 (S15P21A501-102).

`resolver_bench.py` 가 쓰는 순수 함수만 둔다. 외부 의존이 없어 프로젝트 기본 venv 의
pytest 로 검증된다(`tests/test_resolver_eval_metrics.py`).

지표는 joint intent detection + slot filling 의 표준을 따른다 — intent accuracy,
slot F1, sentence-level semantic frame accuracy.

문자 단위 F1(KLUE-NER 방식)은 **두지 않는다.** `resolve_query` 가 돌려주는 것은
validator 가 span 을 이미 고친 뒤의 결과라, 그걸로 문자 단위를 재면 모델이 아니라
validator 를 재게 된다(실측: 값이 맞은 anchor 에서 0.995로 붙박이). 모델의 span 정확도는
`span_exact_rate`(validator 가 고친 비율)가 재고, 경계가 갈리는 복합 지명은 골드셋의
`optional` 이 값 수준에서 처리한다.

`load_gold` 는 라벨 자체의 기계적 오류를 로드 단계에서 거부한다. 골드셋이 200문항이라
사람이 전부 검토할 수 없고, 잘못된 라벨은 조용히 "모델이 틀렸다"로 둔갑한다.
"""

from __future__ import annotations

import hashlib
import json
import random
import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Final

#: (배열명, type, value). type 이 없는 배열(incident_names)은 None.
Slot = tuple[str, str | None, str]

#: anchor 를 담는 배열. `expanded_terms` 는 anchor 가 아니라 제외한다 — 슬롯으로 세면
#: 확장어를 많이 내는 모델이 근거 없이 이긴다.
_TYPED_ARRAYS: Final[tuple[str, ...]] = ("entities", "locations", "classifications")
_UNTYPED_ARRAYS: Final[tuple[str, ...]] = ("incident_names",)

_YEAR: Final[re.Pattern[str]] = re.compile(r"^(?P<y>\d{4})년$")
_MONTH: Final[re.Pattern[str]] = re.compile(r"^(?P<y>\d{4})년\s*(?P<m>\d{1,2})월$")
_DAY: Final[re.Pattern[str]] = re.compile(r"^(?P<y>\d{4})년\s*(?P<m>\d{1,2})월\s*(?P<d>\d{1,2})일$")

_CI_LOW: Final[float] = 0.025
_CI_HIGH: Final[float] = 0.975


@dataclass(frozen=True, slots=True)
class GoldQuery:
    """질의 하나의 정답. `slots` 는 반드시 나와야 하고 `optional` 은 나와도 된다."""

    id: int
    domain: str
    query: str
    intent: str
    slots: frozenset[Slot]
    #: 프롬프트가 두 갈래를 모두 허용하는 값(예: "추석" → incident_names 또는
    #: expanded_terms). recall 에서 빼고 precision 에서는 정답으로 인정한다.
    optional: frozenset[Slot]
    #: anchor 에 들어가면 안 되는 도메인 상투어·요청 어미.
    forbidden: tuple[str, ...]
    tags: frozenset[str]
    rationale: str


# ── 예측 → 슬롯 ───────────────────────────────────────────────────────


def to_slots(resolution: Mapping[str, Any], *, origin: str | None = None) -> frozenset[Slot]:
    """해석 결과를 슬롯 집합으로 바꾼다.

    `origin` 을 주면 그 origin 인 anchor 만 남긴다 — hallucination 판정은
    `explicit_query` 만 본다(`inferred` 는 원문에 없어도 되는 값이다).
    """
    out: set[Slot] = set()
    for window in resolution.get("date_windows") or ():
        if origin is not None and window.get("origin") != origin:
            continue
        span = f"{window['start']}~{window['end_exclusive']}"
        out.add(("date_windows", window["field"], span))
    for array in _UNTYPED_ARRAYS:
        for item in resolution.get(array) or ():
            if origin is not None and item.get("origin") != origin:
                continue
            out.add((array, None, item["value"]))
    for array in _TYPED_ARRAYS:
        for item in resolution.get(array) or ():
            if origin is not None and item.get("origin") != origin:
                continue
            out.add((array, item.get("type"), item["value"]))
    return frozenset(out)


def to_spans(resolution: Mapping[str, Any]) -> dict[Slot, tuple[int, int]]:
    """`query_span` 을 가진 anchor 의 원문 오프셋.

    **`date_windows` 는 뺀다.** 이 값은 `span_exact_rate` 의 분모로 쓰이는데, 분자가 되는
    `span_corrected` 를 `validator.py` 는 날짜에 대해 만들지 않는다 — 날짜는 범위를 벗어나면
    바로 강등한다. 날짜를 분모에만 넣으면 구조적으로 항상 "정확"으로 세어지고, 질의당 날짜
    비중이 모델마다 달라서 부풀림이 균일하지도 않다(실측: gpt-4o-mini 0.238 → 0.424,
    gpt-4.1 0.708 → 0.755).
    """
    spans: dict[Slot, tuple[int, int]] = {}
    for array in (*_UNTYPED_ARRAYS, *_TYPED_ARRAYS):
        for item in resolution.get(array) or ():
            span = item.get("query_span")
            if not span:
                continue
            type_ = item.get("type") if array in _TYPED_ARRAYS else None
            spans[(array, type_, item["value"])] = (int(span["start"]), int(span["end"]))
    return spans


# ── 슬롯 지표 ─────────────────────────────────────────────────────────


def slot_prf(
    pred: frozenset[Slot], gold: frozenset[Slot], optional: frozenset[Slot]
) -> tuple[float, float, float]:
    """precision / recall / F1.

    precision 의 분자는 gold 와 optional 의 합집합 과의 교집합이고, recall 의 분모는 **필수 슬롯만**
    이다. 그래서 허용된 선택지를 낸 모델이 손해를 보지 않고, 안 낸 모델도 손해를 보지 않는다.

    비어 있는 쪽은 1.0 으로 둔다. 예측이 없으면 recall 이 이미 0 이라 precision 까지
    0 으로 매기면 같은 실패를 두 번 벌하게 된다.
    """
    allowed = gold | optional
    precision = len(pred & allowed) / len(pred) if pred else 1.0
    recall = len(pred & gold) / len(gold) if gold else 1.0
    if precision + recall == 0:
        return precision, recall, 0.0
    f1 = 2 * precision * recall / (precision + recall)
    return precision, recall, f1


def frame_hit(pred_intent: str, pred: frozenset[Slot], g: GoldQuery) -> bool:
    """intent 와 슬롯이 **전부** 맞았는가. 가장 엄격하고 서비스 체감에 제일 가깝다."""
    return pred_intent == g.intent and g.slots <= pred <= (g.slots | g.optional)


def leak_hit(pred: frozenset[Slot], forbidden: Sequence[str]) -> bool:
    """'자료화면'·'찾아줘' 같은 상투어가 anchor 로 샜는가 (FRD F-04~05 누수)."""
    return any(token in value for _, _, value in pred for token in forbidden)


#: validator 가 explicit anchor 를 강등할 때 쓰는 action. 원문에 값이 문자 그대로 없다는 뜻이다.
DEMOTED: Final[str] = "demoted_to_inferred"


def hallucination_hit(finding_actions: Iterable[str]) -> bool:
    """원문에 없는 값을 `explicit_query` 로 냈는가 (FRD F-05).

    **판정은 validator 가 한다.** 골드셋에 없는 anchor 를 세는 방식은 두 가지로 틀린다 —
    `slot_precision` 과 같은 것을 재게 되고, 어휘가 열린 축(`scene_type`)에서는 규칙을
    지킨 출력까지 창작으로 찍는다(실측: 그 방식으로 gpt-4.1 이 0.515 였다).

    `finding_actions` 는 `AnchorFinding.action` 문자열이거나 그것을 포함한 줄이면 된다.
    """
    return any(DEMOTED in action for action in finding_actions)


#: validator 가 span 오프셋을 고쳤을 때 쓰는 action.
SPAN_CORRECTED: Final[str] = "span_corrected"
#: anchor 를 결과에서 아예 뺄 때 쓰는 action.
DROPPED: Final[str] = "dropped"


def span_corrected_count(finding_lines: Iterable[str]) -> int:
    """`span_exact_rate` 의 분자. **나중에 drop 된 path 는 세지 않는다.**

    validator 는 span 을 먼저 고치고 그 다음 중복 anchor 를 버린다. 버려진 anchor 는
    결과에 남지 않으므로 분모(`to_spans`)에서도 사라지는데, 분자에만 남으면 비율이
    1 을 넘거나 음수가 된다(실측: `gpt-4o-mini` id=98 이 분모 0 에 분자 1).

    finding 줄은 `"<path> <action>: <reason>"` 형식이고 path 에는 공백이 없다.
    """
    lines = list(finding_lines)
    dropped = {line.split(" ", 1)[0] for line in lines if f" {DROPPED}:" in line}
    return sum(
        1
        for line in lines
        if f" {SPAN_CORRECTED}:" in line and line.split(" ", 1)[0] not in dropped
    )


# ── 유의성 ────────────────────────────────────────────────────────────


def bootstrap_ci(diffs: Sequence[float], *, n: int = 10_000, seed: int = 0) -> tuple[float, float]:
    """질의별로 짝지은 차이의 평균에 대한 95% 신뢰구간.

    모든 모델이 같은 질의를 풀기 때문에 짝지으면 질의 난이도가 상쇄된다.
    구간이 0 을 포함하면 "차이 없음"이 아니라 **"이 표본으로는 검출 못 함"** 이다.
    """
    if not diffs:
        return (0.0, 0.0)
    rng = random.Random(seed)
    size = len(diffs)
    means = sorted(sum(rng.choices(diffs, k=size)) / size for _ in range(n))
    return means[int(n * _CI_LOW)], means[min(int(n * _CI_HIGH), n - 1)]


# ── 골드셋 ────────────────────────────────────────────────────────────


def _expected_window(span_text: str) -> tuple[str, str] | None:
    """프롬프트 '날짜 구간' 규칙이 정하는 반열린 구간. 규칙 밖 표현이면 None."""
    if (m := _DAY.fullmatch(span_text.strip())) is not None:
        start = date(int(m["y"]), int(m["m"]), int(m["d"]))
        return start.isoformat(), (start + timedelta(days=1)).isoformat()
    if (m := _MONTH.fullmatch(span_text.strip())) is not None:
        year, month = int(m["y"]), int(m["m"])
        start = date(year, month, 1)
        end = date(year + 1, 1, 1) if month == 12 else date(year, month + 1, 1)
        return start.isoformat(), end.isoformat()
    if (m := _YEAR.fullmatch(span_text.strip())) is not None:
        year = int(m["y"])
        return date(year, 1, 1).isoformat(), date(year + 1, 1, 1).isoformat()
    return None


def _locate(query: str, span_text: str, where: str) -> tuple[int, int]:
    start = query.find(span_text)
    if start < 0:
        msg = f"{where}: span_text {span_text!r} 가 원문에 없다 — {query!r}"
        raise ValueError(msg)
    return start, start + len(span_text)


def _build(entry: Mapping[str, Any], common_forbidden: tuple[str, ...] = ()) -> GoldQuery:
    query = entry["query"]
    where = f"gold id={entry['id']}"
    slots: set[Slot] = set()
    optional: set[Slot] = set()

    for window in entry.get("date_windows") or ():
        slot: Slot = (
            "date_windows",
            window["field"],
            f"{window['start']}~{window['end_exclusive']}",
        )
        span_text = window.get("span_text")
        if span_text:
            expected = _expected_window(span_text)
            if expected is not None and expected != (window["start"], window["end_exclusive"]):
                msg = (
                    f"{where}: 날짜 구간 규칙 위반 — {span_text!r} 는 "
                    f"{expected[0]}~{expected[1]} 여야 하는데 "
                    f"{window['start']}~{window['end_exclusive']} 로 적혀 있다"
                )
                raise ValueError(msg)
            _locate(query, span_text, where)  # 라벨 검증. 실패하면 로드가 멈춘다
        (optional if window.get("optional") else slots).add(slot)

    for array in (*_UNTYPED_ARRAYS, *_TYPED_ARRAYS):
        for item in entry.get(array) or ():
            type_ = item.get("type") if array in _TYPED_ARRAYS else None
            slot = (array, type_, item["value"])
            span_text = item.get("span_text")
            if span_text:
                _locate(query, span_text, where)  # 라벨 검증. 실패하면 로드가 멈춘다
            elif item.get("origin") == "explicit_query":
                _locate(query, item["value"], where)
            (optional if item.get("optional") else slots).add(slot)

    return GoldQuery(
        id=int(entry["id"]),
        domain=entry["domain"],
        query=query,
        intent=entry["intent"],
        slots=frozenset(slots),
        optional=frozenset(optional),
        forbidden=tuple(dict.fromkeys((*common_forbidden, *(entry.get("forbidden") or ())))),
        tags=frozenset(entry.get("tags") or ()),
        rationale=entry.get("rationale", ""),
    )


def load_gold(path: Path) -> tuple[GoldQuery, ...]:
    """골드셋을 읽고 라벨 자체를 검증한다. 어긋나면 측정 전에 멈춘다."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    common = tuple(data.get("forbidden_common") or ())
    gold = tuple(_build(entry, common) for entry in data["queries"])
    ids = [g.id for g in gold]
    if len(set(ids)) != len(ids):
        duplicated = sorted({i for i in ids if ids.count(i) > 1})
        msg = f"골드셋 id 가 중복된다: {duplicated}"
        raise ValueError(msg)
    return gold


def gold_hash(gold: Iterable[GoldQuery]) -> str:
    """골드셋 내용 해시. 결과 파일과 MLflow param 에 실어 어떤 라벨로 잰 값인지 남긴다."""
    payload = [
        {
            "id": g.id,
            "query": g.query,
            "intent": g.intent,
            "slots": sorted(map(list, g.slots)),
            "optional": sorted(map(list, g.optional)),
            "forbidden": list(g.forbidden),
        }
        for g in sorted(gold, key=lambda x: x.id)
    ]
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]
