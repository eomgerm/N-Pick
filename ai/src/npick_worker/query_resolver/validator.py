"""LLM 출력 검증 3단계 (FR-QRY-011·012·014·016, AC-QRY-005).

    1) schema        JSON 파싱 · 필드 · enum · 범위      실패 → 복구하지 않고 예외
    2) semantic      span 대조 · 날짜 구간 · 중복         실패 → 3단계로
    3) safe          explicit → inferred 강등 · 중복 제거  통과

1단계와 2·3단계의 차이가 이 파일의 핵심이다. **모양이 깨진 출력은 복구하지 않는다** —
JSON 이 아니거나 enum 이 틀리면 무엇을 의도했는지 알 수 없다. 반면 "원문에 없는 것을
사용자가 쓴 것처럼 표시한" 출력은 의도가 분명하므로, 거짓 근거만 떼어내고 값은 살린다.

`FR-QRY-011` 이 그 방식을 지정한다 — "span 검증 실패 시 `inferred`로 강등해야 한다".
거부가 아니라 강등이다.

검증 과정은 전부 `AnchorFinding` 으로 남는다. `query_resolution_snapshot` 의
`explicit_anchor_validation_json` 이 NOT NULL 이라 통과했을 때도 기록이 있어야 한다.
"""

import json
from dataclasses import dataclass
from datetime import date
from typing import Final

from pydantic import ValidationError

from npick_worker.query_resolver.schema import (
    RESOLVER_ORIGINS,
    DateWindow,
    Entity,
    Location,
    QuerySpan,
    RawResolution,
    ValidatedResolution,
    ValuedAnchor,
)

#: FRD §12 의 오류 코드. Search Service 가 degraded 사유로 기록한다(`FR-QRY-022`).
RESOLVER_SCHEMA_INVALID: Final[str] = "RESOLVER_SCHEMA_INVALID"


class ResolverSchemaInvalidError(ValueError):
    """1단계 실패. 호출부는 raw query BM25 fallback 으로 내려간다(`FR-QRY-022`)."""

    code = RESOLVER_SCHEMA_INVALID


@dataclass(frozen=True, slots=True)
class AnchorFinding:
    """검증이 무언가를 바꿨다는 기록.

    비어 있으면 "LLM 출력을 그대로 통과시켰다" 는 뜻이다. 그것도 기록할 가치가 있다.
    """

    #: 어느 항목인가. 예: `entities[1]`
    path: str
    #: 무엇을 했나. `demoted_to_inferred` | `dropped`
    action: str
    #: 왜 했나. 사람이 읽는 문장이다.
    reason: str


@dataclass(frozen=True, slots=True)
class ValidationOutcome:
    resolution: ValidatedResolution
    findings: tuple[AnchorFinding, ...]

    def to_validation_json(self) -> list[dict[str, str]]:
        """`explicit_anchor_validation_json` 에 실을 형태."""
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
    entities = _drop_entities_shadowed_by_locations(
        tuple(
            _check_value_anchor(item, f"entities[{i}]", query, findings)
            for i, item in enumerate(raw.entities)
        ),
        locations,
        findings,
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
    """`value` 를 갖는 anchor: span 이 원문에서 정확히 그 문자열이어야 한다.

    구체 타입을 그대로 돌려준다. 유니온으로 받으면 `entities` 자리에 `Location` 을 넣어도
    타입 검사를 통과해 버린다 — 이 파일이 막으려는 것이 바로 그런 뒤섞임이다.
    """
    reason = _explicit_claim_problem(anchor.origin, anchor.query_span, query, anchor.value)
    if reason is None:
        return anchor
    findings.append(AnchorFinding(path, "demoted_to_inferred", reason))
    return anchor.model_copy(update={"origin": "inferred", "query_span": None})


def _demote_unless_span_in_range(
    window: DateWindow, path: str, query: str, findings: list[AnchorFinding]
) -> DateWindow:
    reason = _explicit_claim_problem(window.origin, window.query_span, query, expected=None)
    if reason is None:
        return window
    findings.append(AnchorFinding(path, "demoted_to_inferred", reason))
    return window.model_copy(update={"origin": "inferred", "query_span": None})


def _explicit_claim_problem(
    origin: str, span: QuerySpan | None, query: str, expected: str | None
) -> str | None:
    """`explicit_query` 주장이 원문으로 뒷받침되는지. 문제가 없으면 `None`.

    `expected` 가 `None` 이면 span 범위만 본다(날짜 구간처럼 대조할 문자열이 없는 경우).
    """
    if origin not in RESOLVER_ORIGINS:
        # FR-QRY-012 — explicit UI filter 는 사용자만 만든다. resolver 가 이걸 내는 것은
        # 사용자 조건을 위조하는 것이다. 다만 값 자체는 원문에서 왔을 수 있으므로
        # 검색 전체를 실패시키지 않고 출처 주장만 떼어낸다.
        return f"resolver 가 만들 수 없는 origin 이다: {origin}"
    if origin != "explicit_query":
        return None
    if span is None:
        return "explicit_query 인데 query_span 이 없다"
    if span.end <= span.start:
        return f"query_span 이 빈 구간이다: [{span.start}, {span.end})"
    if span.end > len(query):
        return f"query_span 이 원문 길이를 넘는다: end={span.end}, len={len(query)}"
    if expected is not None and query[span.start : span.end] != expected:
        return (
            f"query_span 이 원문과 다르다: 원문[{span.start}:{span.end}]="
            f"{query[span.start : span.end]!r} != {expected!r}"
        )
    return None


def _drop_entities_shadowed_by_locations(
    entities: tuple[Entity, ...], locations: tuple[Location, ...], findings: list[AnchorFinding]
) -> tuple[Entity, ...]:
    """`AC-QRY-005` — 같은 값이 entities 와 locations 에 동시에 오면 한 건만 남긴다.

    수용 기준은 "거부 또는 locations 한 건으로 정규화" 둘 다 허용한다. **locations 를
    남긴다** — 검색 단위가 장면이고, 같은 문자열이 양쪽으로 읽힐 때(예: `서울시청`)
    장면 화면에 실제로 보이는 것은 장소·시설 쪽이기 때문이다. 반대로 지우면
    이중 scoring 이 아니라 신호 자체가 사라진다.
    """
    taken = {_fold(item.value) for item in locations}
    kept: list[Entity] = []
    for i, entity in enumerate(entities):
        if _fold(entity.value) in taken:
            findings.append(
                AnchorFinding(
                    f"entities[{i}]",
                    "dropped",
                    f"locations 에 같은 값이 있다: {entity.value!r} (AC-QRY-005)",
                )
            )
            continue
        kept.append(entity)
    return tuple(kept)


def _fold(value: str) -> str:
    """중복 판정용 비교 키. `match_value` 정규화가 아니다 — 그건 Search 쪽 몫이다."""
    return " ".join(value.split()).casefold()
