"""LLM 출력 검증: schema → semantic → 안전한 결과 (FRD F-04~06).

JSON·필드·enum 검증 실패는 예외로 반환한다. 원문에 있는 값의 span은 직접 찾아
보정하고, 없는 값이나 resolver가 주장한 explicit_filter는 inferred로 강등한다.
이는 F-05의 명시 조건·추론 구분과 사용자 필터 보호를 충족하기 위한 구현 선택이다.
뒤집힌 날짜 구간 삭제 역시 구현 선택이다. **entities 와 locations 의 교차 중복은
거르지 않는다** — 종류가 다르면 이름이 같아도 다른 조건이기 때문이다(F-05 구조화 축
점수, S15P21A501-205). inferred만으로 강제 제외하지 않는 것은 검색 호출부 책임이다(F-06).
변경 내역은 AnchorFinding으로 반환하여 §7.2 기록을 지원한다. 특정 DB 구조를 전제하지 않는다.
"""

import json
from dataclasses import dataclass
from datetime import date
from typing import Final

from pydantic import ValidationError

from npick_worker.query_resolver.schema import (
    RESOLVER_ORIGINS,
    DateWindow,
    QuerySpan,
    RawResolution,
    ValidatedResolution,
    ValuedAnchor,
)

#: 모듈 오류 코드. Search Service 가 degraded 사유로 기록한다(`FRD §6.2`).
RESOLVER_SCHEMA_INVALID: Final[str] = "RESOLVER_SCHEMA_INVALID"


class ResolverSchemaInvalidError(ValueError):
    """1단계 실패. 호출부는 raw query BM25 fallback 으로 내려간다(`FRD §6.2`)."""

    code = RESOLVER_SCHEMA_INVALID


@dataclass(frozen=True, slots=True)
class AnchorFinding:
    """검증이 무언가를 바꿨다는 기록.

    비어 있으면 "LLM 출력을 그대로 통과시켰다" 는 뜻이다. 그것도 기록할 가치가 있다.
    """

    #: 어느 항목인가. 예: `entities[1]`
    path: str
    #: 무엇을 했나. `span_corrected` | `demoted_to_inferred` | `dropped`
    action: str
    #: 왜 했나. 사람이 읽는 문장이다.
    reason: str


@dataclass(frozen=True, slots=True)
class ValidationOutcome:
    resolution: ValidatedResolution
    findings: tuple[AnchorFinding, ...]

    def to_validation_json(self) -> list[dict[str, str]]:
        """호출자가 기록할 findings의 JSON 직렬화 형태."""
        return [{"path": f.path, "action": f.action, "reason": f.reason} for f in self.findings]


def parse_raw(payload: str) -> RawResolution:
    """1단계. 모양이 깨졌으면 복구하지 않는다.

    코드펜스를 벗기는 정도만 봐준다 — 프롬프트가 금지하지만 모델이 자주 붙이고,
    이건 의도가 명확해서 복구가 추측이 아니다.
    """
    text = _strip_code_fence(payload.strip())
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        msg = f"resolver 출력이 JSON 이 아니다: {exc}"
        raise ResolverSchemaInvalidError(msg) from exc
    if not isinstance(data, dict):
        msg = f"resolver 출력이 객체가 아니다: {type(data).__name__}"
        raise ResolverSchemaInvalidError(msg)
    try:
        return RawResolution.model_validate(data)
    except ValidationError as exc:
        msg = f"resolver 출력이 schema 와 맞지 않는다: {exc}"
        raise ResolverSchemaInvalidError(msg) from exc


def _strip_code_fence(text: str) -> str:
    if not text.startswith("```"):
        return text
    lines = text.splitlines()
    # 첫 줄은 ``` 또는 ```json. 마지막 ``` 줄까지 잘라낸다.
    body = lines[1:]
    if body and body[-1].strip() == "```":
        body = body[:-1]
    return "\n".join(body)


def validate(raw: RawResolution, query: str) -> ValidationOutcome:
    """2·3단계. 원문 `query` 와 대조한다.

    `query` 는 프롬프트에 넣은 것과 **같은 문자열**이어야 한다. canonical query 를
    넣으면 span 이 전부 어긋나 모든 anchor 가 강등된다.
    """
    findings: list[AnchorFinding] = []

    date_windows = _validate_date_windows(raw.date_windows, query, findings)
    incident_names = tuple(
        _check_value_anchor(item, f"incident_names[{i}]", query, findings)
        for i, item in enumerate(raw.incident_names)
    )
    locations = tuple(
        _check_value_anchor(item, f"locations[{i}]", query, findings)
        for i, item in enumerate(raw.locations)
    )
    entities = tuple(
        _check_value_anchor(item, f"entities[{i}]", query, findings)
        for i, item in enumerate(raw.entities)
    )
    classifications = tuple(
        _check_value_anchor(item, f"classifications[{i}]", query, findings)
        for i, item in enumerate(raw.classifications)
    )

    resolution = ValidatedResolution(
        intent=raw.intent,
        date_windows=date_windows,
        incident_names=incident_names,
        entities=entities,
        locations=locations,
        classifications=classifications,
        expanded_terms=raw.expanded_terms,
        confidence=raw.confidence,
    )
    return ValidationOutcome(resolution=resolution, findings=tuple(findings))


def _validate_date_windows(
    windows: tuple[DateWindow, ...], query: str, findings: list[AnchorFinding]
) -> tuple[DateWindow, ...]:
    """날짜 구간은 `value` 가 없어 span 을 문자열과 대조할 수 없다.

    그래서 범위만 본다. 대신 `[start, end_exclusive)` 가 실제로 반열린 구간인지
    검사하고, 아니면 **버린다** — 뒤집힌 구간은 강등해도 쓸 수 없다.
    """
    kept: list[DateWindow] = []
    for i, window in enumerate(windows):
        path = f"date_windows[{i}]"
        bounds = _parse_window_bounds(window)
        if bounds is None:
            findings.append(
                AnchorFinding(
                    path, "dropped", f"날짜 형식이 아니다: {window.start}~{window.end_exclusive}"
                )
            )
            continue
        start, end = bounds
        if start >= end:
            findings.append(
                AnchorFinding(path, "dropped", f"start 가 end_exclusive 이상이다: {start} >= {end}")
            )
            continue
        kept.append(_demote_unless_span_in_range(window, path, query, findings))
    return tuple(kept)


def _parse_window_bounds(window: DateWindow) -> tuple[date, date] | None:
    try:
        return date.fromisoformat(window.start), date.fromisoformat(window.end_exclusive)
    except ValueError:
        return None


def _check_value_anchor[A: ValuedAnchor](
    anchor: A, path: str, query: str, findings: list[AnchorFinding]
) -> A:
    """`value` 를 갖는 anchor: 그 문자열이 원문에 있어야 하고, span 은 코드가 찾는다.

    구체 타입을 그대로 돌려준다. 유니온으로 받으면 `entities` 자리에 `Location` 을 넣어도
    타입 검사를 통과해 버린다 — 이 파일이 막으려는 것이 바로 그런 뒤섞임이다.
    """
    if anchor.origin not in RESOLVER_ORIGINS:
        # FRD F-05 — explicit UI filter 는 사용자만 만든다. resolver 가 이걸 내는 것은
        # 사용자 조건을 위조하는 것이다. 다만 값 자체는 원문에서 왔을 수 있으므로
        # 검색 전체를 실패시키지 않고 출처 주장만 떼어낸다.
        reason = f"resolver 가 만들 수 없는 origin 이다: {anchor.origin}"
        findings.append(AnchorFinding(path, "demoted_to_inferred", reason))
        return anchor.model_copy(update={"origin": "inferred", "query_span": None})
    if anchor.origin != "explicit_query":
        return anchor

    located = _locate(anchor.value, query, anchor.query_span)
    if located is None:
        # 여기가 창작 anchor 가 걸러지는 자리다(FRD F-05).
        reason = f"원문에 없는 값을 explicit_query 로 주장했다: {anchor.value!r}"
        findings.append(AnchorFinding(path, "demoted_to_inferred", reason))
        return anchor.model_copy(update={"origin": "inferred", "query_span": None})
    if located == anchor.query_span:
        return anchor

    claimed = (
        f"[{anchor.query_span.start}, {anchor.query_span.end})"
        if anchor.query_span is not None
        else "없음"
    )
    reason = f"모델 span {claimed} 을 원문에서 찾은 [{located.start}, {located.end}) 로 고쳤다"
    findings.append(AnchorFinding(path, "span_corrected", reason))
    return anchor.model_copy(update={"query_span": located})


def _locate(value: str, query: str, hint: QuerySpan | None) -> QuerySpan | None:
    """`value` 가 원문에 있으면 그 위치를 돌려준다. 없으면 `None`.

    같은 문자열이 여러 번 나오면 모델이 준 위치에 **가장 가까운** 것을 고른다.
    모델의 숫자는 신뢰할 값이 아니지만 어느 쪽을 가리켰는지에 대한 힌트로는 쓸 수 있다.
    힌트가 없으면 첫 번째를 쓴다.
    """
    starts = [i for i in range(len(query) - len(value) + 1) if query.startswith(value, i)]
    if not starts:
        return None
    start = starts[0] if hint is None else min(starts, key=lambda s: abs(s - hint.start))
    return QuerySpan(start=start, end=start + len(value))


def _demote_unless_span_in_range(
    window: DateWindow, path: str, query: str, findings: list[AnchorFinding]
) -> DateWindow:
    """날짜 구간은 대조할 문자열이 없어 span 을 찾아줄 수 없다. 범위만 본다."""
    reason = _explicit_claim_problem(window.origin, window.query_span, query)
    if reason is None:
        return window
    findings.append(AnchorFinding(path, "demoted_to_inferred", reason))
    return window.model_copy(update={"origin": "inferred", "query_span": None})


def _explicit_claim_problem(origin: str, span: QuerySpan | None, query: str) -> str | None:
    """span 범위만 보는 검사. 대조할 문자열이 없는 날짜 구간에만 쓴다.

    값을 갖는 anchor 는 `_check_value_anchor` 가 span 을 직접 찾으므로 이 함수를
    쓰지 않는다.
    """
    if origin not in RESOLVER_ORIGINS:
        # FRD F-05 — explicit UI filter 는 사용자만 만든다.
        return f"resolver 가 만들 수 없는 origin 이다: {origin}"
    if origin != "explicit_query":
        return None
    if span is None:
        return "explicit_query 인데 query_span 이 없다"
    if span.end <= span.start:
        return f"query_span 이 빈 구간이다: [{span.start}, {span.end})"
    if span.end > len(query):
        return f"query_span 이 원문 길이를 넘는다: end={span.end}, len={len(query)}"
    return None
