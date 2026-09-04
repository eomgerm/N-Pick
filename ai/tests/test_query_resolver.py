"""Query Resolver 검증 테스트 (FR-QRY-010~016, AC-QRY-005).

전부 LLM 없이 돈다. 고정된 출력 문자열을 validator 에 넣어 검사한다 — 모델 응답을
채점하는 것은 Gold Set 이 있어야 하는 일이고(FRD §14), 여기서 할 일이 아니다.

설계 §17 의 Hard/Soft 구분에서 **Hard 쪽**이다. Soft(확장어가 적절한가, 추론이 과한가)는
`report.py` 로 사람이 본다.
"""

import ast
import json
import os
from collections.abc import Iterable
from pathlib import Path
from typing import Any

import httpx2
import pytest
from pydantic import SecretStr

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
from npick_worker.query_resolver.gms_backend import GmsResolver
from npick_worker.query_resolver.prompt import BROADCAST_FIELD, FILMING_FIELD
from npick_worker.query_resolver.report import _build_resolver, load_queries
from npick_worker.query_resolver.resolver import ResolverCallError
from npick_worker.settings import Settings

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


# ── GMS backend (OpenAI 호환) ─────────────────────────────────────────


class _FakeResponse:
    def __init__(self, status_code: int, body: Any) -> None:
        self.status_code = status_code
        self._body = body

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            msg = f"{self.status_code} error"
            raise httpx2.HTTPError(msg)

    def json(self) -> Any:
        return self._body


class _FakeClient:
    """`httpx2.Client` 대역. 요청을 기록하고 정해진 응답을 돌려준다.

    `GmsResolver` 가 Client 를 스스로 만든다(adapter 경계 안). transport 를 생성자로
    받게 하면 httpx 타입이 경계 밖으로 새므로, 대신 모듈 이름공간을 갈아끼운다.
    """

    calls: list[dict[str, Any]] = []  # noqa: RUF012 - 테스트 대역이다

    def __init__(self, **kwargs: Any) -> None:
        self.kwargs = kwargs
        self.response: _FakeResponse | Exception = _FakeResponse(200, {})

    def __enter__(self) -> "_FakeClient":
        return self

    def __exit__(self, *exc: object) -> None:
        return None

    def post(self, path: str, *, json: Any) -> _FakeResponse:
        type(self).calls.append({"path": path, "json": json, "client": self.kwargs})
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


def _patch_client(
    monkeypatch: pytest.MonkeyPatch, response: _FakeResponse | Exception
) -> list[dict[str, Any]]:
    calls: list[dict[str, Any]] = []

    def factory(**kwargs: Any) -> _FakeClient:
        client = _FakeClient(**kwargs)
        client.response = response
        return client

    _FakeClient.calls = calls
    monkeypatch.setattr(httpx2, "Client", factory)
    return calls


def _ok_body(content: str, model: str = "gateway-model-2026") -> dict[str, Any]:
    return {"model": model, "choices": [{"message": {"role": "assistant", "content": content}}]}


def _gms(**overrides: Any) -> GmsResolver:
    kwargs: dict[str, Any] = {
        "base_url": "https://gms.example/api/",
        "api_key": "sk-do-not-log-me",
        "model": "some-model",
        "params": get_default_config().call,
    }
    kwargs.update(overrides)
    return GmsResolver(**kwargs)


@pytest.mark.parametrize(
    ("missing", "env"),
    [
        ("base_url", "NPICK_AI_GMS_BASE_URL"),
        ("api_key", "NPICK_AI_GMS_API_KEY"),
        ("model", "NPICK_AI_GMS_MODEL"),
    ],
)
def test_gms_missing_setting_names_the_env_var(missing: str, env: str) -> None:
    """설정 실수는 무엇을 해야 하는지로 알려준다. 기본값으로 때우지 않는다."""
    with pytest.raises(ValueError, match=env):
        _gms(**{missing: ""})


def test_gms_sends_openai_chat_shape(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = _patch_client(monkeypatch, _FakeResponse(200, _ok_body('{"intent":"unknown"}')))
    assert _gms().complete("SYS", "USR") == '{"intent":"unknown"}'

    (call,) = calls
    assert call["path"] == "/v1/chat/completions"
    body = call["json"]
    assert body["messages"] == [
        {"role": "system", "content": "SYS"},
        {"role": "user", "content": "USR"},
    ]
    # Ollama 와 다른 두 지점.
    assert body["max_tokens"] == get_default_config().call.max_output_tokens
    assert body["response_format"] == {"type": "json_object"}
    assert call["client"]["headers"]["Authorization"].endswith("sk-do-not-log-me")
    # base_url 의 끝 슬래시가 남으면 //v1/... 이 된다.
    assert call["client"]["base_url"] == "https://gms.example/api"


def test_gms_json_mode_can_be_turned_off(monkeypatch: pytest.MonkeyPatch) -> None:
    """게이트웨이가 response_format 을 거부하면 끈다. 자동 재시도는 없다(FR-QRY-023)."""
    calls = _patch_client(monkeypatch, _FakeResponse(200, _ok_body("{}")))
    _gms(json_mode=False).complete("SYS", "USR")
    assert "response_format" not in calls[0]["json"]


def test_gms_version_prefers_the_model_the_gateway_reports(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """별칭을 보냈는데 게이트웨이가 구체 버전을 돌려주면 그걸 기록한다."""
    _patch_client(monkeypatch, _FakeResponse(200, _ok_body("{}", model="gpt-x-2026-01-01")))
    resolver = _gms(model="gpt-x")
    assert resolver.version == "gpt-x"  # 호출 전에는 설정값. 지어내지 않는다
    resolver.complete("SYS", "USR")
    assert resolver.version == "gpt-x-2026-01-01"


def test_gms_rate_limit_maps_to_frd_error_code(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_client(monkeypatch, _FakeResponse(429, {}))
    with pytest.raises(ResolverCallError) as exc:
        _gms().complete("SYS", "USR")
    assert exc.value.category == "RESOLVER_RATE_LIMITED"


def test_gms_timeout_maps_to_frd_error_code(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_client(monkeypatch, httpx2.TimeoutException("too slow"))
    with pytest.raises(ResolverCallError) as exc:
        _gms().complete("SYS", "USR")
    assert exc.value.category == "RESOLVER_TIMEOUT"


def test_gms_http_error_does_not_leak_the_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    """키는 헤더에만 있다. 오류 문자열로 새면 로그·스냅샷에 그대로 남는다."""
    _patch_client(monkeypatch, _FakeResponse(500, {}))
    with pytest.raises(ResolverCallError) as exc:
        _gms().complete("SYS", "USR")
    assert exc.value.category == "RESOLVER_NETWORK"
    assert "sk-do-not-log-me" not in str(exc.value)


def test_gms_response_without_content_is_a_call_error(monkeypatch: pytest.MonkeyPatch) -> None:
    """빈 응답을 빈 문자열로 넘기면 RESOLVER_SCHEMA_INVALID 로 잘못 기록된다."""
    _patch_client(monkeypatch, _FakeResponse(200, {"choices": []}))
    with pytest.raises(ResolverCallError) as exc:
        _gms().complete("SYS", "USR")
    assert exc.value.category == "RESOLVER_NETWORK"


def _settings(monkeypatch: pytest.MonkeyPatch, **overrides: Any) -> Settings:
    """개발자 머신의 `.env`·환경 변수가 테스트 결과를 바꾸지 않게 격리한다."""
    for name in list(os.environ):
        if name.startswith("NPICK_AI_"):
            monkeypatch.delenv(name)
    return Settings(_env_file=None, **overrides)  # type: ignore[call-arg]


# ── backend 선택 ──────────────────────────────────────────────────────


def test_backend_default_is_local(monkeypatch: pytest.MonkeyPatch) -> None:
    """FRD §13.4 가 외부 전송을 별도 승인 대상으로 둔다. 기본은 나가지 않는 쪽이다."""
    assert _settings(monkeypatch).resolver_backend == "ollama"


def test_backend_selection_follows_the_setting(monkeypatch: pytest.MonkeyPatch) -> None:
    params = get_default_config().call
    gms = _build_resolver(
        _settings(
            monkeypatch,
            resolver_backend="gms",
            gms_base_url="https://gms.example",
            gms_api_key=SecretStr("k"),
            gms_model="m",
        ),
        params,
    )
    assert gms.name == "gms"

    local = _build_resolver(
        _settings(monkeypatch, resolver_backend="ollama", ollama_model="qwen2.5:7b"), params
    )
    assert local.name == "ollama"


def test_api_key_setting_is_masked_in_repr(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = _settings(monkeypatch, gms_api_key=SecretStr("sk-do-not-log-me"))
    assert "sk-do-not-log-me" not in repr(settings)
    assert settings.gms_api_key.get_secret_value() == "sk-do-not-log-me"
