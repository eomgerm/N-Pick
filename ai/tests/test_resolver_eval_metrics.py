"""Query Resolver 평가 지표 단위 테스트 (S15P21A501-102).

`eval/query_resolver/` 의 채점 함수와 러너의 재시도·스레드 배선만 검증한다. 네트워크도
mlflow 도 필요 없어 **프로젝트 기본 venv 의 pytest 로 돈다** — 평가용 venv(.venv-eval)는
실제 측정을 돌릴 때만 필요하다. `tests/test_embedding_metrics.py` 와 같은 구조다.

하네스는 `src/npick_worker` 밖에 있으므로 경로를 직접 얹어 import 한다.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "eval" / "query_resolver"))

from metrics import (
    GoldQuery,
    bootstrap_ci,
    frame_hit,
    gold_hash,
    hallucination_hit,
    leak_hit,
    load_gold,
    slot_prf,
    to_slots,
    to_spans,
)
from resolver_bench import call_with_backoff, dump, gms_factory, rescore, run_model

from npick_worker.query_resolver import (
    ResolverCallError,
    get_default_config,
)

# ── 도우미 ────────────────────────────────────────────────────────────


def _anchor(
    value: str, start: int, end: int, *, type_: str | None = None, origin: str = "explicit_query"
) -> dict[str, Any]:
    item: dict[str, Any] = {
        "value": value,
        "origin": origin,
        "query_span": {"start": start, "end": end} if origin == "explicit_query" else None,
        "confidence": 0.9,
    }
    if type_ is not None:
        item["type"] = type_
    return item


def _resolution(**kw: Any) -> dict[str, Any]:
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
    base.update(kw)
    return base


def _gold_file(tmp_path: Path, queries: list[dict[str, Any]]) -> Path:
    import json

    tmp_path.mkdir(parents=True, exist_ok=True)
    target = tmp_path / "gold.json"
    target.write_text(
        json.dumps({"schema": "query-resolver-gold/v1", "queries": queries}, ensure_ascii=False),
        encoding="utf-8",
    )
    return target


def _gold_entry(**kw: Any) -> dict[str, Any]:
    entry: dict[str, Any] = {
        "id": 1,
        "domain": "교통·인프라",
        "query": "서울역 인파 화면 찾아줘",
        "intent": "scene_search",
        "date_windows": [],
        "incident_names": [],
        "entities": [],
        "locations": [],
        "classifications": [],
        "forbidden": ["화면", "찾아줘"],
        "tags": [],
        "rationale": "테스트용",
    }
    entry.update(kw)
    return entry


# ── to_slots ──────────────────────────────────────────────────────────


def test_to_slots_ignores_expanded_terms() -> None:
    """expanded_terms 는 anchor 가 아니다. 슬롯으로 세면 확장어를 많이 내는 모델이 이긴다."""
    slots = to_slots(_resolution(expanded_terms=["귀성", "인파", "승객"]))
    assert slots == frozenset()


def test_to_slots_normalizes_date_window_to_single_slot() -> None:
    slots = to_slots(
        _resolution(
            date_windows=[
                {
                    "field": "broadcast_date",
                    "start": "2022-01-01",
                    "end_exclusive": "2023-01-01",
                    "origin": "explicit_query",
                    "query_span": {"start": 0, "end": 5},
                    "confidence": 0.9,
                }
            ]
        )
    )
    assert slots == frozenset({("date_windows", "broadcast_date", "2022-01-01~2023-01-01")})


def test_to_slots_can_filter_by_origin() -> None:
    """hallucination 판정은 explicit_query 만 본다 — inferred 는 원문에 없어도 된다."""
    resolution = _resolution(
        locations=[
            _anchor("서울역", 0, 3, type_="facility"),
            _anchor("부산", 0, 0, type_="location", origin="inferred"),
        ]
    )
    assert len(to_slots(resolution)) == 2
    assert to_slots(resolution, origin="explicit_query") == frozenset(
        {("locations", "facility", "서울역")}
    )


# ── slot_prf ──────────────────────────────────────────────────────────

_A: frozenset[tuple[str, str | None, str]] = frozenset({("locations", "facility", "서울역")})
_B: frozenset[tuple[str, str | None, str]] = frozenset({("entities", "person", "홍길동")})
_EMPTY: frozenset[tuple[str, str | None, str]] = frozenset()


def test_slot_prf_perfect_match() -> None:
    assert slot_prf(_A, _A, _EMPTY) == (1.0, 1.0, 1.0)


def test_slot_prf_optional_predicted_does_not_cost_precision() -> None:
    """프롬프트가 '추석 → incident_names 또는 expanded_terms' 둘 다 허용한다.
    허용된 출력을 오답으로 세면 규칙을 지킨 모델이 손해를 본다."""
    p, r, f = slot_prf(_A | _B, _A, _B)
    assert (p, r, f) == (1.0, 1.0, 1.0)


def test_slot_prf_optional_omitted_does_not_cost_recall() -> None:
    p, r, f = slot_prf(_A, _A, _B)
    assert (p, r, f) == (1.0, 1.0, 1.0)


def test_slot_prf_empty_both_sides_is_one_without_zero_division() -> None:
    assert slot_prf(_EMPTY, _EMPTY, _EMPTY) == (1.0, 1.0, 1.0)


def test_slot_prf_missing_required_slot_drops_recall_only() -> None:
    p, r, f = slot_prf(_EMPTY, _A, _EMPTY)
    assert p == 1.0
    assert r == 0.0
    assert f == 0.0


def test_slot_prf_extra_slot_drops_precision() -> None:
    p, r, _ = slot_prf(_A | _B, _A, _EMPTY)
    assert p == pytest.approx(0.5)
    assert r == 1.0


# ── to_spans ─────────────────────────────────────────────────────────


def test_to_spans_excludes_date_windows() -> None:
    """`span_total` 의 분모로 쓰이는데, validator 는 date_windows 에 대해
    `span_corrected` 를 **절대 만들지 않는다**(강등만 한다). 날짜를 분모에 넣으면
    구조적으로 항상 '정확'으로 세어져 모델마다 다른 크기로 지표가 부푼다
    (실측: gpt-4o-mini 0.238 → 0.424).
    """
    spans = to_spans(
        _resolution(
            date_windows=[
                {
                    "field": "broadcast_date",
                    "start": "2022-01-01",
                    "end_exclusive": "2023-01-01",
                    "origin": "explicit_query",
                    "query_span": {"start": 0, "end": 5},
                    "confidence": 0.9,
                }
            ],
            locations=[_anchor("서울역", 6, 9, type_="facility")],
        )
    )
    assert spans == {("locations", "facility", "서울역"): (6, 9)}


# ── frame_hit / leak_hit / hallucination_hit ──────────────────────────


def _gq(
    intent: str = "scene_search",
    slots: frozenset[Any] = _A,
    optional: frozenset[Any] = _EMPTY,
    forbidden: tuple[str, ...] = (),
) -> GoldQuery:
    return GoldQuery(
        id=1,
        domain="교통·인프라",
        query="서울역 인파 화면 찾아줘",
        intent=intent,
        slots=slots,
        optional=optional,
        forbidden=forbidden,
        tags=frozenset(),
        rationale="",
    )


def test_frame_hit_requires_matching_intent() -> None:
    assert frame_hit("recent_scene", _A, _gq()) is False


def test_frame_hit_fails_on_one_missing_slot() -> None:
    assert frame_hit("scene_search", _EMPTY, _gq()) is False


def test_frame_hit_allows_optional_slot() -> None:
    assert frame_hit("scene_search", _A | _B, _gq(optional=_B)) is True


def test_frame_hit_fails_on_extra_slot() -> None:
    assert frame_hit("scene_search", _A | _B, _gq()) is False


def test_leak_hit_detects_boilerplate_in_anchor() -> None:
    leaked = frozenset({("classifications", "scene_type", "자료화면")})
    assert leak_hit(leaked, ("자료화면", "찾아줘")) is True
    assert leak_hit(_A, ("자료화면", "찾아줘")) is False


def test_hallucination_hit_reads_validator_demotion() -> None:
    """원문에 없는 값을 explicit_query 로 냈는지는 **validator 가 판정한다**(FRD F-05).

    골드셋에 없는 anchor 를 세는 방식은 안 된다 — 그건 slot_precision 과 같은 것을 재고,
    어휘가 열린 축(scene_type)에서는 규칙을 지킨 출력까지 창작으로 찍는다.
    """
    assert hallucination_hit(["locations.0 demoted_to_inferred"]) is True
    assert hallucination_hit(["locations.0 span_corrected"]) is False
    assert hallucination_hit([]) is False


# ── bootstrap_ci ──────────────────────────────────────────────────────


def test_bootstrap_ci_is_deterministic_for_a_seed() -> None:
    diffs = [0.1, -0.05, 0.2, 0.0, 0.15, -0.1, 0.05, 0.3]
    assert bootstrap_ci(diffs, n=500, seed=7) == bootstrap_ci(diffs, n=500, seed=7)


def test_bootstrap_ci_excludes_zero_when_all_diffs_positive() -> None:
    lo, hi = bootstrap_ci([0.2] * 40, n=500, seed=0)
    assert lo > 0.0
    assert hi >= lo


def test_bootstrap_ci_includes_zero_when_diffs_straddle() -> None:
    lo, hi = bootstrap_ci([0.5, -0.5] * 40, n=500, seed=0)
    assert lo <= 0.0 <= hi


# ── load_gold / gold_hash ─────────────────────────────────────────────


def test_load_gold_rejects_span_text_absent_from_query(tmp_path: Path) -> None:
    """라벨 오타가 span 지표를 조용히 망가뜨리는 걸 막는다. 200개를 사람이 다 볼 수 없다."""
    path = _gold_file(
        tmp_path,
        [
            _gold_entry(
                locations=[
                    {
                        "type": "facility",
                        "value": "부산역",
                        "origin": "explicit_query",
                        "span_text": "부산역",
                    }
                ]
            )
        ],
    )
    with pytest.raises(ValueError, match="원문에 없다"):
        load_gold(path)


def test_load_gold_rejects_year_window_that_breaks_the_prompt_rule(tmp_path: Path) -> None:
    """프롬프트 '날짜 구간' 규칙: 연도만 주어지면 연 단위 반열린 구간이다.
    라벨이 그걸 어기면 규칙을 지킨 모델이 오답 처리된다."""
    path = _gold_file(
        tmp_path,
        [
            _gold_entry(
                query="2022년 서울역 화면 찾아줘",
                date_windows=[
                    {
                        "field": "broadcast_date",
                        "start": "2022-09-01",
                        "end_exclusive": "2022-10-01",
                        "origin": "explicit_query",
                        "span_text": "2022년",
                    }
                ],
            )
        ],
    )
    with pytest.raises(ValueError, match="날짜 구간"):
        load_gold(path)


def test_load_gold_accepts_rule_conformant_year_window(tmp_path: Path) -> None:
    path = _gold_file(
        tmp_path,
        [
            _gold_entry(
                query="2022년 서울역 화면 찾아줘",
                date_windows=[
                    {
                        "field": "broadcast_date",
                        "start": "2022-01-01",
                        "end_exclusive": "2023-01-01",
                        "origin": "explicit_query",
                        "span_text": "2022년",
                    }
                ],
            )
        ],
    )
    (gold,) = load_gold(path)
    assert ("date_windows", "broadcast_date", "2022-01-01~2023-01-01") in gold.slots


def test_load_gold_merges_common_forbidden(tmp_path: Path) -> None:
    """상투어 금지 목록은 200문항 공통이다. 문항마다 반복해 적으면 한 곳만 빠뜨린다."""
    import json

    tmp_path.mkdir(parents=True, exist_ok=True)
    target = tmp_path / "gold.json"
    target.write_text(
        json.dumps(
            {
                "schema": "query-resolver-gold/v1",
                "forbidden_common": ["자료화면", "찾아줘"],
                "queries": [_gold_entry(forbidden=["인파"])],
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    (gold,) = load_gold(target)
    assert set(gold.forbidden) == {"자료화면", "찾아줘", "인파"}


def test_gold_hash_is_stable_and_content_sensitive(tmp_path: Path) -> None:
    a = load_gold(_gold_file(tmp_path / "a", [_gold_entry()]))
    b = load_gold(_gold_file(tmp_path / "b", [_gold_entry()]))
    c = load_gold(_gold_file(tmp_path / "c", [_gold_entry(query="부산역 인파 화면 찾아줘")]))
    assert gold_hash(a) == gold_hash(b)
    assert gold_hash(a) != gold_hash(c)


# ── 러너: 재시도와 스레드 배선 ────────────────────────────────────────


class _ScriptedResolver:
    """정해진 순서대로 예외나 응답을 돌려준다."""

    def __init__(self, script: list[Any]) -> None:
        self._script = list(script)

    @property
    def name(self) -> str:
        return "scripted"

    @property
    def version(self) -> str:
        return "scripted-1"

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        step = self._script.pop(0)
        if isinstance(step, Exception):
            raise step
        return str(step)


_OK_PAYLOAD = (
    '{"intent":"scene_search","date_windows":[],"incident_names":[],"entities":[],'
    '"locations":[],"classifications":[],"expanded_terms":[],"confidence":0.9}'
)


def _rate_limited() -> ResolverCallError:
    return ResolverCallError("429", category="RESOLVER_RATE_LIMITED")


def test_call_with_backoff_recovers_after_rate_limit() -> None:
    """벤치에서 429 를 그냥 실패로 세면 요율 제한이 모델 점수로 둔갑한다."""
    resolver = _ScriptedResolver([_rate_limited(), _rate_limited(), _OK_PAYLOAD])
    outcome = call_with_backoff(
        lambda: resolver, "서울역", get_default_config(), max_retry=3, sleep=lambda _: None
    )
    assert outcome.error is None
    assert outcome.result is not None
    assert outcome.retries == 2


def test_call_with_backoff_gives_up_and_reports_failure() -> None:
    resolver = _ScriptedResolver([_rate_limited()] * 4)
    outcome = call_with_backoff(
        lambda: resolver, "서울역", get_default_config(), max_retry=3, sleep=lambda _: None
    )
    assert outcome.result is None
    assert outcome.error is not None
    assert "RESOLVER_RATE_LIMITED" in outcome.error
    assert outcome.retries == 3


def test_call_with_backoff_does_not_retry_non_rate_limit_errors() -> None:
    """timeout·schema 오류까지 재시도하면 그 모델의 실패율이 가려진다."""
    resolver = _ScriptedResolver([ResolverCallError("timeout", category="RESOLVER_TIMEOUT")])
    outcome = call_with_backoff(
        lambda: resolver, "서울역", get_default_config(), max_retry=3, sleep=lambda _: None
    )
    assert outcome.result is None
    assert outcome.retries == 0


def test_gms_factory_gives_one_instance_per_thread(monkeypatch: pytest.MonkeyPatch) -> None:
    """GmsResolver 는 _reported_model 을 인스턴스에 쓴다(gms_backend.py). 인스턴스를
    공유하면 model_version 기록이 스레드 간에 섞인다."""
    import threading
    from concurrent.futures import ThreadPoolExecutor

    monkeypatch.setenv("NPICK_AI_GMS_BASE_URL", "https://example.invalid/v1/chat/completions")
    monkeypatch.setenv("NPICK_AI_GMS_API_KEY", "test-key")
    factory = gms_factory("gpt-4.1", get_default_config().call)

    workers = 4
    # barrier 가 없으면 짧은 작업이 한 스레드에서 다 끝나 동시성이 재현되지 않는다.
    barrier = threading.Barrier(workers)

    def twice() -> tuple[int, int]:
        first = factory()
        barrier.wait(timeout=5)
        return id(first), id(factory())

    with ThreadPoolExecutor(max_workers=workers) as pool:
        pairs = list(pool.map(lambda _: twice(), range(workers)))

    assert all(first == second for first, second in pairs)  # 한 스레드 안에서는 재사용
    assert len({first for first, _ in pairs}) == workers  # 스레드마다 다른 인스턴스


def test_rescore_reproduces_metrics_without_calling_the_api(tmp_path: Path) -> None:
    """지표 정의가 바뀔 때마다 200문항 x 6모델을 다시 호출하면 크레딧이 든다.
    저장된 해석 결과로 같은 값이 나와야 재집계를 믿고 쓸 수 있다."""
    gold = load_gold(
        _gold_file(tmp_path, [_gold_entry(id=i, intent="scene_search") for i in range(1, 5)])
    )
    live = run_model(
        "stub",
        gold,
        get_default_config(),
        timeout_s=5.0,
        concurrency=1,
        resolver_factory=lambda: _ScriptedResolver([_OK_PAYLOAD] * 10),
    )
    out = tmp_path / "results.json"
    dump([live], out)

    (again,) = rescore(out, gold)
    assert again.metrics == live.metrics
    assert again.params["rescored_from"] == str(out)


def test_run_model_micro_f1_never_exceeds_one(tmp_path: Path) -> None:
    """micro recall 의 분자는 **필수 슬롯과의 교집합**이어야 한다.

    precision 쪽 분자(gold+optional)를 recall 에도 쓰면, optional 을 낸 모델에서
    분자가 분모(필수 슬롯 수)를 넘어 F1 이 1을 초과한다. 실측에서 1.027 이 나왔다.
    """
    entry = _gold_entry(
        query="서울역 인파 화면 찾아줘",
        locations=[
            {"type": "facility", "value": "서울역", "origin": "explicit_query"},
            {
                "type": "facility",
                "value": "서울역 인파",
                "origin": "explicit_query",
                "optional": True,
            },
        ],
    )
    gold = load_gold(_gold_file(tmp_path, [entry]))
    payload = (
        '{"intent":"scene_search","date_windows":[],"incident_names":[],"entities":[],'
        '"locations":['
        '{"type":"facility","value":"서울역","origin":"explicit_query",'
        '"query_span":{"start":0,"end":3},"confidence":0.9},'
        '{"type":"facility","value":"서울역 인파","origin":"explicit_query",'
        '"query_span":{"start":0,"end":6},"confidence":0.9}],'
        '"classifications":[],"expanded_terms":[],"confidence":0.9}'
    )
    result = run_model(
        "stub",
        gold,
        get_default_config(),
        timeout_s=5.0,
        concurrency=1,
        resolver_factory=lambda: _ScriptedResolver([payload]),
    )
    assert result.metrics["slot_recall"] <= 1.0
    assert result.metrics["slot_f1"] <= 1.0


def test_run_model_scores_every_gold_query(tmp_path: Path) -> None:
    """실패한 호출도 채점 대상이다 — 살아남은 응답만 세면 많이 실패한 모델이 좋아 보인다."""
    gold = load_gold(
        _gold_file(tmp_path, [_gold_entry(id=i, intent="scene_search") for i in range(1, 9)])
    )
    result = run_model(
        "stub",
        gold,
        get_default_config(),
        timeout_s=5.0,
        concurrency=4,
        resolver_factory=lambda: _ScriptedResolver([_OK_PAYLOAD] * 50),
    )
    assert len(result.per_query) == 8
    assert result.metrics["schema_valid_rate"] == 1.0
    assert result.metrics["intent_accuracy"] == 1.0
