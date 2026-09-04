"""Query Resolver 검증 테스트 (FR-QRY-010~016, AC-QRY-005).

전부 LLM 없이 돈다. 고정된 출력 문자열을 validator 에 넣어 검사한다 — 모델 응답을
채점하는 것은 Gold Set 이 있어야 하는 일이고(FRD §14), 여기서 할 일이 아니다.

설계 §17 의 Hard/Soft 구분에서 **Hard 쪽**이다. Soft(확장어가 적절한가, 추론이 과한가)는
`report.py` 로 사람이 본다.
"""

import ast
import json
from collections.abc import Iterable
from pathlib import Path
from typing import Any

import pytest

from npick_worker.query_resolver import (
    SCHEMA_VERSION,
    DateField,
    QueryResolverConfig,
    ResolverSchemaInvalidError,
    empty_resolution,
    get_default_config,
    load_config,
    parse_raw,
    render_system_prompt,
    render_user_prompt,
    resolve_query,
    validate,
)
from npick_worker.query_resolver.config import DEFAULT_CONFIG_PATH
from npick_worker.query_resolver.prompt import BROADCAST_FIELD, FILMING_FIELD
from npick_worker.query_resolver.report import load_queries

SRC_ROOT = Path(__file__).resolve().parent.parent / "src" / "npick_worker" / "query_resolver"


def _payload(**overrides: Any) -> str:
    """최소 유효 출력. 필요한 배열만 덮어쓴다."""
    base: dict[str, Any] = {
        "intent": "scene_search",
        "date_windows": [],
        "incident_names": [],
        "entities": [],
        "locations": [],
        "classifications": [],
        "expanded_terms": [],
        "confidence": 0.9,
    }
    base.update(overrides)
    return json.dumps(base, ensure_ascii=False)


def _anchor(value: str, start: int, end: int, **extra: Any) -> dict[str, Any]:
    return {
        "value": value,
        "origin": "explicit_query",
        "query_span": {"start": start, "end": end},
        "confidence": 0.9,
        **extra,
    }


class StubResolver:
    """`QueryResolver` 대역. 프롬프트를 기록하고 정해진 문자열을 돌려준다."""

    name = "stub"
    version = "stub-model@abc123"

    def __init__(self, reply: str) -> None:
        self.reply = reply
        self.seen: tuple[str, str] | None = None

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        self.seen = (system_prompt, user_prompt)
        return self.reply


# ── 1단계: schema ─────────────────────────────────────────────────────


def test_non_json_output_is_schema_invalid() -> None:
    with pytest.raises(ResolverSchemaInvalidError):
        parse_raw("죄송합니다, JSON 을 만들 수 없습니다.")


def test_unknown_field_is_rejected_not_ignored() -> None:
    """모르는 키를 조용히 버리면 '프롬프트를 고쳤는데 출력이 그대로'를 못 잡는다."""
    with pytest.raises(ResolverSchemaInvalidError):
        parse_raw(_payload(rewritten_query="서울역 인파"))


def test_confidence_out_of_range_is_rejected() -> None:
    with pytest.raises(ResolverSchemaInvalidError):
        parse_raw(_payload(confidence=1.5))


def test_code_fence_is_stripped() -> None:
    """프롬프트가 금지하지만 모델이 자주 붙인다. 복구가 추측이 아니라 봐준다."""
    raw = parse_raw(f"```json\n{_payload()}\n```")
    assert raw.intent == "scene_search"


def test_json_array_is_rejected() -> None:
    with pytest.raises(ResolverSchemaInvalidError):
        parse_raw("[]")


# ── 2·3단계: span 검증과 강등 (FR-QRY-011) ────────────────────────────


def test_matching_span_stays_explicit() -> None:
    query = "서울역 귀성객"
    outcome = validate(
        parse_raw(_payload(locations=[_anchor("서울역", 0, 3, type="facility")])), query
    )
    assert outcome.resolution.locations[0].origin == "explicit_query"
    assert outcome.findings == ()


def test_mismatched_span_is_demoted_not_rejected() -> None:
    """FR-QRY-011 은 '거부'가 아니라 'inferred 로 강등'을 지정한다."""
    query = "서울역 귀성객"
    outcome = validate(
        parse_raw(_payload(locations=[_anchor("부산역", 0, 3, type="facility")])), query
    )
    location = outcome.resolution.locations[0]
    assert location.origin == "inferred"
    assert location.query_span is None
    assert location.value == "부산역"
    assert outcome.findings[0].action == "demoted_to_inferred"


def test_span_beyond_query_length_is_demoted() -> None:
    outcome = validate(
        parse_raw(_payload(entities=[_anchor("홍길동", 0, 99, type="person")])), "홍길동"
    )
    assert outcome.resolution.entities[0].origin == "inferred"


def test_explicit_without_span_is_demoted() -> None:
    """FR-QRY-014 — 원문에 없는 값을 사용자 조건처럼 만들지 못하게 한다."""
    payload = _payload(
        incident_names=[
            {"value": "추석", "origin": "explicit_query", "query_span": None, "confidence": 0.9}
        ]
    )
    outcome = validate(parse_raw(payload), "서울역 사람 많은 장면")
    assert outcome.resolution.incident_names[0].origin == "inferred"


def test_resolver_cannot_forge_explicit_filter() -> None:
    """FR-QRY-012 — explicit UI filter 는 사용자만 만든다."""
    payload = _payload(
        entities=[
            {
                "type": "person",
                "value": "홍길동",
                "origin": "explicit_filter",
                "query_span": {"start": 0, "end": 3},
                "confidence": 0.9,
            }
        ]
    )
    outcome = validate(parse_raw(payload), "홍길동 인터뷰")
    assert outcome.resolution.entities[0].origin == "inferred"


def test_inferred_anchor_needs_no_span() -> None:
    payload = _payload(
        classifications=[
            {
                "type": "scene_type",
                "value": "crowd",
                "origin": "inferred",
                "query_span": None,
                "confidence": 0.5,
            }
        ]
    )
    outcome = validate(parse_raw(payload), "사람 많은 공항")
    assert outcome.findings == ()


# ── 날짜 (FR-QRY-013) ─────────────────────────────────────────────────


def _window(field: str, start: str, end: str, **extra: Any) -> dict[str, Any]:
    return {
        "field": field,
        "start": start,
        "end_exclusive": end,
        "origin": "inferred",
        "query_span": None,
        "confidence": 0.9,
        **extra,
    }


def test_year_window_is_half_open() -> None:
    payload = _payload(date_windows=[_window(BROADCAST_FIELD, "2022-01-01", "2023-01-01")])
    outcome = validate(parse_raw(payload), "2022년 뉴스")
    assert outcome.resolution.date_windows[0].end_exclusive == "2023-01-01"


def test_reversed_window_is_dropped() -> None:
    """뒤집힌 구간은 강등해도 쓸 수 없다. 버린다."""
    payload = _payload(date_windows=[_window(BROADCAST_FIELD, "2023-01-01", "2022-01-01")])
    outcome = validate(parse_raw(payload), "2022년 뉴스")
    assert outcome.resolution.date_windows == ()
    assert outcome.findings[0].action == "dropped"


def test_unknown_date_field_is_schema_invalid() -> None:
    with pytest.raises(ResolverSchemaInvalidError):
        parse_raw(_payload(date_windows=[_window("mentioned_date", "2022-01-01", "2023-01-01")]))


def test_both_date_fields_are_accepted() -> None:
    for field in (BROADCAST_FIELD, FILMING_FIELD):
        payload = _payload(date_windows=[_window(field, "2022-01-01", "2023-01-01")])
        assert validate(parse_raw(payload), "2022년").resolution.date_windows[0].field == field


# ── entities / locations 중복 (FR-QRY-016, AC-QRY-005) ────────────────


def test_duplicate_value_keeps_locations_only() -> None:
    query = "서울시청 앞 인파"
    payload = _payload(
        entities=[_anchor("서울시청", 0, 4, type="organization")],
        locations=[_anchor("서울시청", 0, 4, type="facility")],
    )
    outcome = validate(parse_raw(payload), query)
    assert outcome.resolution.entities == ()
    assert len(outcome.resolution.locations) == 1
    assert "AC-QRY-005" in outcome.findings[0].reason


def test_duplicate_check_ignores_case_and_spacing() -> None:
    payload = _payload(
        entities=[
            {
                "type": "organization",
                "value": "KBS  뉴스",
                "origin": "inferred",
                "query_span": None,
                "confidence": 0.5,
            }
        ],
        locations=[
            {
                "type": "facility",
                "value": "kbs 뉴스",
                "origin": "inferred",
                "query_span": None,
                "confidence": 0.5,
            }
        ],
    )
    assert validate(parse_raw(payload), "kbs 뉴스").resolution.entities == ()


def test_different_values_both_survive() -> None:
    payload = _payload(
        entities=[_anchor("홍길동", 0, 3, type="person")],
        locations=[_anchor("서울역", 4, 7, type="facility")],
    )
    outcome = validate(parse_raw(payload), "홍길동 서울역")
    assert len(outcome.resolution.entities) == 1
    assert len(outcome.resolution.locations) == 1


# ── 버전 (FR-OVR-005, DB 컬럼) ────────────────────────────────────────


def test_schema_version_is_added_by_code_not_model() -> None:
    """LLM 이 버전 문자열을 지어내면 그 자체가 거짓 기록이다."""
    assert "schema_version" not in _payload()
    assert validate(parse_raw(_payload()), "q").resolution.schema_version == SCHEMA_VERSION


def test_prompt_version_changes_with_prompt_text() -> None:
    base = get_default_config()
    edited = base.model_copy(update={"system_prompt": base.system_prompt + " "})
    assert edited.prompt_version != base.prompt_version


def test_prompt_version_is_stable_across_loads() -> None:
    assert load_config().prompt_version == load_config(DEFAULT_CONFIG_PATH).prompt_version


def test_versioned_config_filename_must_match_schema(tmp_path: Path) -> None:
    target = tmp_path / "query_resolver.v2.toml"
    target.write_text(
        DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"), encoding="utf-8"
    )  # schema 는 v1 인 채로
    with pytest.raises(ValueError, match="일치하지 않는다"):
        load_config(target)


def test_empty_resolution_carries_schema_version() -> None:
    """FR-QRY-024 — fallback 도 validated resolution 이고, DB 컬럼이 NOT NULL 이다."""
    assert empty_resolution().schema_version == SCHEMA_VERSION
    assert empty_resolution().intent == "unknown"


# ── 프롬프트 (FR-QRY-013~016) ─────────────────────────────────────────


def test_prompt_uses_schema_date_field_names() -> None:
    """프롬프트와 schema 가 다른 이름을 말하면 출력이 항상 검증에서 떨어진다."""
    rendered = render_system_prompt(get_default_config())
    assert "{broadcast_field}" not in rendered
    assert "{filming_field}" not in rendered
    assert BROADCAST_FIELD in rendered
    assert FILMING_FIELD in rendered


def test_user_prompt_carries_original_query() -> None:
    rendered = render_user_prompt(get_default_config(), "서울역 귀성객")
    assert "서울역 귀성객" in rendered
    assert "{query}" not in rendered


def test_prompt_states_the_five_rules() -> None:
    """규칙이 빠지면 프롬프트가 1차 방어를 못 한다(티켓 제약)."""
    rendered = render_system_prompt(get_default_config())
    for token in ("explicit_query", "inferred", "query_span", "expanded_terms", "촬영"):
        assert token in rendered


# ── Gate B: 프롬프트가 코드에 없어야 한다 ─────────────────────────────


def _string_literals(source: str) -> Iterable[str]:
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            yield node.value


def test_prompt_body_is_not_in_python_sources() -> None:
    """ai/AGENTS.md — Gate B 미동결 값은 코드에 두지 않는다.

    프롬프트 규칙 문장이 `.py` 에 복사돼 있으면 toml 을 고쳐도 그쪽이 안 바뀐다.
    """
    marker = "재작성한 질의문을 만들지 않는다"
    assert marker in DEFAULT_CONFIG_PATH.read_text(encoding="utf-8")
    for path in SRC_ROOT.rglob("*.py"):
        literals = set(_string_literals(path.read_text(encoding="utf-8")))
        assert not any(marker in text for text in literals), path


# ── 픽스처 (완료 조건) ────────────────────────────────────────────────


def test_representative_queries_cover_twenty_cases() -> None:
    queries = load_queries()
    assert len(queries) == 20
    assert [q["id"] for q in queries] == list(range(1, 21))
    assert all(q["query"].strip() and q["checks"].strip() for q in queries)


def test_hallucination_case_is_present() -> None:
    """15번이 FR-QRY-014 확인 케이스다. 빠지면 완료 조건을 못 본다."""
    case = next(q for q in load_queries() if q["id"] == 15)
    assert case["query"] == "서울역 사람 많은 장면"


# ── 전체 경로 ─────────────────────────────────────────────────────────


def test_resolve_query_reports_three_versions() -> None:
    stub = StubResolver(_payload(locations=[_anchor("서울역", 0, 3, type="facility")]))
    result = resolve_query("서울역 귀성객", stub)
    assert result.resolution_schema_version == SCHEMA_VERSION
    assert result.prompt_version.startswith("query-resolver-prompt/v1:")
    assert result.model_version == "stub-model@abc123"


def test_resolve_query_sends_rendered_prompts() -> None:
    stub = StubResolver(_payload())
    resolve_query("서울역 귀성객", stub)
    assert stub.seen is not None
    system, user = stub.seen
    assert BROADCAST_FIELD in system
    assert "서울역 귀성객" in user


def test_resolve_query_propagates_schema_error() -> None:
    """FR-QRY-022 — 호출부가 fallback 으로 내려갈 수 있게 예외를 그대로 올린다."""
    with pytest.raises(ResolverSchemaInvalidError):
        resolve_query("서울역", StubResolver("not json"))


def test_findings_serialize_for_snapshot_column() -> None:
    """explicit_anchor_validation_json 이 NOT NULL 이라 항상 실을 값이 있어야 한다."""
    outcome = validate(
        parse_raw(_payload(entities=[_anchor("부산역", 0, 3, type="person")])), "서울역"
    )
    payload = outcome.to_validation_json()
    assert json.loads(json.dumps(payload))[0]["action"] == "demoted_to_inferred"


def test_date_field_literal_is_single_source() -> None:
    """정본이 v2.2/v3.0 으로 갈려 있어 바뀔 수 있다. 한 곳에서만 정의되어야 한다."""
    from typing import get_args

    assert get_args(DateField) == (BROADCAST_FIELD, FILMING_FIELD)


def test_config_rejects_unknown_toml_key(tmp_path: Path) -> None:
    target = tmp_path / "experiment.toml"
    target.write_text(
        DEFAULT_CONFIG_PATH.read_text(encoding="utf-8") + '\nunknown_key = "x"\n',
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="unknown_key"):
        load_config(target)


def test_config_type_is_frozen() -> None:
    cfg: QueryResolverConfig = get_default_config()
    with pytest.raises(ValueError, match="frozen"):
        cfg.system_prompt = "x"
