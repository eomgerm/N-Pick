"""검색 회귀 하네스 지표 단위 테스트 (S15P21A501-301).

`eval/search/search_metrics.py` 는 외부 의존이 없다 — DB 도 API 도 부르지 않는다.
그래서 이 파일은 프로젝트 기본 venv 의 pytest 로 돈다
(`eval/query_resolver` 의 `tests/test_resolver_eval_metrics.py` 와 같은 구조).
"""

from __future__ import annotations

import json
import sys
from math import log2
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "eval" / "search"))

import search_metrics as metrics


def case(case_id: str, scene_id: str, tier: metrics.Tier = "core") -> metrics.Case:
    return metrics.Case(
        id=case_id,
        query=f"질의 {case_id}",
        scene_id=scene_id,
        clip_id="c1",
        keyframe="runs/1/frame_extraction/a1/s0000/kf-000000001.jpg",
        rationale="테스트",
        tier=tier,
        human_reviewed=False,
    )


# ── recall_at_k ──────────────────────────────────────────────────────


def test_recall_is_one_when_gold_is_within_top_k() -> None:
    assert metrics.recall_at_k(["a", "b", "c"], "c", 3) == 1.0


def test_recall_is_zero_when_gold_is_beyond_k() -> None:
    assert metrics.recall_at_k(["a", "b", "c"], "c", 2) == 0.0


def test_recall_is_zero_for_empty_results() -> None:
    assert metrics.recall_at_k([], "a", 10) == 0.0


def test_recall_tolerates_k_larger_than_results() -> None:
    assert metrics.recall_at_k(["a"], "a", 10) == 1.0


def test_recall_is_zero_when_gold_is_absent() -> None:
    assert metrics.recall_at_k(["a", "b"], "z", 10) == 0.0


# ── ndcg_at_k ────────────────────────────────────────────────────────


def test_ndcg_is_one_at_rank_one() -> None:
    # 이진 관련성·단일 정답이라 IDCG = 1/log2(2) = 1 이고 nDCG = 1/log2(rank+1) 이다.
    assert metrics.ndcg_at_k(["a", "b"], "a", 10) == pytest.approx(1.0)


def test_ndcg_follows_one_over_log2_rank_plus_one() -> None:
    assert metrics.ndcg_at_k(["a", "b", "c"], "c", 10) == pytest.approx(1 / log2(4))


def test_ndcg_is_zero_beyond_k() -> None:
    assert metrics.ndcg_at_k(["a", "b", "c"], "c", 2) == 0.0


def test_ndcg_is_zero_wherever_recall_is_zero() -> None:
    ranked = [f"s{i}" for i in range(20)]
    for k in (1, 5, 10):
        assert metrics.recall_at_k(ranked, "s15", k) == 0.0 or k > 15
        if metrics.recall_at_k(ranked, "s15", k) == 0.0:
            assert metrics.ndcg_at_k(ranked, "s15", k) == 0.0


def test_ndcg_decreases_monotonically_with_rank() -> None:
    ranked = ["a", "b", "c", "d"]
    scores = [metrics.ndcg_at_k(ranked, s, 10) for s in ranked]
    assert scores == sorted(scores, reverse=True)


def test_ndcg_uses_first_occurrence_on_duplicates() -> None:
    # BE 계약상 한 응답에 같은 scene_id 는 없지만, 그 불변식이 깨져도 점수를
    # 낙관적으로 보정하지 않는다 — 처음 나온 자리가 사용자가 본 자리다.
    assert metrics.ndcg_at_k(["a", "b", "a"], "a", 10) == pytest.approx(1.0)


# ── split ────────────────────────────────────────────────────────────


def test_split_keeps_both_groups() -> None:
    cases = [case("1", "s1"), case("2", "s2"), case("3", "s3", tier="hard")]
    results = {"1": ["s1"], "2": ["x", "y"], "3": []}
    passed, failed = metrics.split(cases, results, k=10)
    assert [c.id for c in passed] == ["1"]
    assert [c.id for c in failed] == ["2", "3"]


def test_split_counts_missing_results_as_failed() -> None:
    # 호출 자체가 실패한 질의를 조용히 빼면 많이 실패한 설정이 좋아 보인다.
    cases = [case("1", "s1")]
    passed, failed = metrics.split(cases, {}, k=10)
    assert not passed
    assert [c.id for c in failed] == ["1"]


def test_split_counts_gold_beyond_k_as_failed() -> None:
    cases = [case("1", "s1")]
    ranked = [f"x{i}" for i in range(10)] + ["s1"]
    passed, failed = metrics.split(cases, {"1": ranked}, k=10)
    assert not passed
    assert [c.id for c in failed] == ["1"]


# ── summarize ────────────────────────────────────────────────────────


def test_summarize_reports_overall_and_per_tier() -> None:
    cases = [case("1", "s1"), case("2", "s2", tier="hard")]
    results = {"1": ["s1"], "2": ["x", "s2"]}
    summary = metrics.summarize(cases, results, k=10)
    assert summary.cases == 2
    assert summary.recall == pytest.approx(1.0)
    assert summary.ndcg == pytest.approx((1.0 + 1 / log2(3)) / 2)
    assert summary.by_tier["core"].recall == pytest.approx(1.0)
    assert summary.by_tier["hard"].ndcg == pytest.approx(1 / log2(3))


def test_summarize_is_zero_without_cases() -> None:
    summary = metrics.summarize([], {}, k=10)
    assert summary.cases == 0
    assert summary.recall == 0.0
    assert summary.ndcg == 0.0


def test_summarize_to_json_rounds() -> None:
    cases = [case("1", "s1")]
    payload = metrics.summarize(cases, {"1": ["x", "s1"]}, k=10).to_json()
    assert payload["cases"] == 1
    assert payload["recallAt10"] == 1.0
    assert payload["ndcgAt10"] == pytest.approx(round(1 / log2(3), 4))


# ── load_gold ────────────────────────────────────────────────────────


def _write_gold(tmp_path: Path, cases: list[dict[str, object]]) -> Path:
    path = tmp_path / "gold.json"
    path.write_text(
        json.dumps({"schema": "search-gold/v1", "cases": cases}, ensure_ascii=False),
        encoding="utf-8",
    )
    return path


def _raw(case_id: str = "sg-001", **over: object) -> dict[str, object]:
    row = {
        "id": case_id,
        "query": "교육청 깃발",
        "sceneId": "888823156852875876",
        "clipId": "888816886045434592",
        "keyframe": "runs/1/frame_extraction/a1/s0002/kf-000004838.jpg",
        "rationale": "키프레임에 교육청 깃발이 보인다",
        "tier": "core",
        "humanReviewed": False,
    }
    row.update(over)
    return row


def test_load_gold_reads_cases(tmp_path: Path) -> None:
    loaded = metrics.load_gold(_write_gold(tmp_path, [_raw()]))
    assert len(loaded) == 1
    assert loaded[0].scene_id == "888823156852875876"
    assert loaded[0].tier == "core"


def test_load_gold_rejects_duplicate_ids(tmp_path: Path) -> None:
    path = _write_gold(tmp_path, [_raw(), _raw()])
    with pytest.raises(metrics.GoldError):
        metrics.load_gold(path)


def test_load_gold_rejects_blank_query(tmp_path: Path) -> None:
    path = _write_gold(tmp_path, [_raw(query="   ")])
    with pytest.raises(metrics.GoldError):
        metrics.load_gold(path)


def test_load_gold_rejects_unknown_tier(tmp_path: Path) -> None:
    path = _write_gold(tmp_path, [_raw(tier="medium")])
    with pytest.raises(metrics.GoldError):
        metrics.load_gold(path)


def test_load_gold_rejects_other_schema(tmp_path: Path) -> None:
    path = tmp_path / "gold.json"
    path.write_text(json.dumps({"schema": "search-gold/v2", "cases": []}), encoding="utf-8")
    with pytest.raises(metrics.GoldError):
        metrics.load_gold(path)


def test_load_gold_rejects_empty_cases(tmp_path: Path) -> None:
    with pytest.raises(metrics.GoldError):
        metrics.load_gold(_write_gold(tmp_path, []))


# ── 커밋된 골드셋 ────────────────────────────────────────────────────


GOLD = Path(__file__).resolve().parents[1] / "eval" / "search" / "gold.json"


def test_committed_gold_loads() -> None:
    loaded = metrics.load_gold(GOLD)
    assert len(loaded) == 200
    assert len({c.scene_id for c in loaded}) == 120
