"""임베딩 평가 지표 단위 테스트 (S15P21A501-175).

`eval/embedding/embedding_bench.py` 의 지표 함수만 검증한다. numpy 외에 의존이 없어
모델도 GPU 도 mlflow 도 필요 없다 — 그래서 평가용 venv 가 아니라 **프로젝트 기본
venv 의 pytest 로 돈다.** 하네스 안의 `--self-check` 로만 두면 아무도 실행하지 않고,
`python -O` 에서는 assert 가 통째로 사라진다.

하네스는 `src/npick_worker` 밖에 있으므로 경로를 직접 얹어 import 한다.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "eval" / "embedding"))

from embedding_bench import (
    ndcg_at_k,
    precision_at_k,
    recall_at_k,
    reciprocal_rank,
    truncate,
)

RANKED = [7, 3, 1, 9, 2]
RELEVANT = {3, 2}  # 2위와 5위에 있다


class TestRecall:
    def test_all_relevant_within_k_gives_one(self) -> None:
        assert recall_at_k(RANKED, RELEVANT, 10) == 1.0

    def test_only_one_relevant_in_top_two(self) -> None:
        assert recall_at_k(RANKED, RELEVANT, 2) == 0.5

    def test_no_relevant_in_top_one(self) -> None:
        assert recall_at_k(RANKED, RELEVANT, 1) == 0.0

    def test_ceiling_is_capped_when_relevant_exceeds_k(self) -> None:
        # category 레벨이 이 상황이다. 정답 100건이면 recall@10 은 최대 0.1 이다.
        assert recall_at_k(list(range(100)), set(range(100)), 10) == 0.1


class TestPrecision:
    def test_one_of_top_two_is_relevant(self) -> None:
        assert precision_at_k(RANKED, RELEVANT, 2) == 0.5

    def test_denominator_is_k_not_relevant_count(self) -> None:
        assert precision_at_k(RANKED, RELEVANT, 10) == 0.2

    def test_not_capped_when_relevant_exceeds_k(self) -> None:
        # recall 이 0.1 로 눌리는 같은 상황에서 precision 은 1.0 을 유지한다.
        assert precision_at_k(list(range(100)), set(range(100)), 10) == 1.0


class TestNdcg:
    def test_matches_hand_computed_value(self) -> None:
        # 정답이 2위·5위 -> DCG = 1/log2(3) + 1/log2(6)
        # IDCG 는 min(|R|, k)=2 개가 1·2위에 있을 때 -> 1/log2(2) + 1/log2(3)
        expected = (1 / np.log2(3) + 1 / np.log2(6)) / (1 / np.log2(2) + 1 / np.log2(3))
        assert ndcg_at_k(RANKED, RELEVANT, 10) == pytest.approx(expected)

    def test_perfect_ranking_gives_one(self) -> None:
        assert ndcg_at_k([3, 2, 7], RELEVANT, 10) == pytest.approx(1.0)

    def test_no_relevant_retrieved_gives_zero(self) -> None:
        assert ndcg_at_k([7, 1, 9], RELEVANT, 10) == 0.0

    def test_empty_relevant_set_does_not_divide_by_zero(self) -> None:
        assert ndcg_at_k(RANKED, set(), 10) == 0.0


class TestReciprocalRank:
    def test_first_relevant_at_rank_two(self) -> None:
        assert reciprocal_rank(RANKED, RELEVANT) == 0.5

    def test_no_relevant_gives_zero(self) -> None:
        assert reciprocal_rank([7, 1, 9], RELEVANT) == 0.0


class TestTruncate:
    def test_result_is_unit_length(self) -> None:
        # 재정규화를 빼면 여기서 걸린다. 정규화된 벡터의 앞부분은 그 자체로 단위가 아니다.
        v = np.array([[3.0, 4.0, 12.0], [1.0, 0.0, 0.0]])
        v = v / np.linalg.norm(v, axis=1, keepdims=True)
        cut = truncate(v, 2)
        assert cut.shape == (2, 2)
        assert np.allclose(np.linalg.norm(cut, axis=1), 1.0)

    def test_keeps_direction_of_surviving_dimensions(self) -> None:
        v = np.array([[3.0, 4.0, 12.0]])
        v = v / np.linalg.norm(v, axis=1, keepdims=True)
        assert np.allclose(truncate(v, 2)[0], [0.6, 0.8])  # 3-4-5 삼각형

    def test_zero_vector_does_not_produce_nan(self) -> None:
        assert np.all(np.isfinite(truncate(np.array([[0.0, 0.0, 1.0]]), 2)))
