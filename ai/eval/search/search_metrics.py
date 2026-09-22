"""검색 회귀 지표 (S15P21A501-301).

`search_bench.py` 가 쓰는 순수 함수만 둔다. DB 도 API 도 부르지 않아 프로젝트 기본
venv 의 pytest 로 검증된다(`tests/test_search_eval_metrics.py`). `eval/ocr/ocr_metrics.py`
와 같은 구조다.

이름이 `metrics.py` 가 아닌 이유는 `pyproject.toml` 의 `mypy_path` 주석이 적은 그대로다 —
`eval/query_resolver/metrics.py` 가 이미 있고, 둘 다 `metrics` 면 mypy 는 앞의 경로 하나로만
해석하고 pytest 는 `sys.modules` 에 먼저 올라온 쪽을 재사용한다. 한쪽 테스트가 **다른
하네스를 검사하고 통과한다.**

## 왜 Recall@10 과 nDCG@10 둘 다인가

`POST /search` 의 한 페이지가 10건이고 편집기자가 보는 것도 그 한 화면이다. 그래서
**k 는 10 으로 고정된 값이 아니라 화면의 크기**다.

- `Recall@10` — 그 화면 안에 원 장면이 있었는가. 있거나 없거나다.
- `nDCG@10` — 있었다면 몇 번째였는가. 이진 관련성에 정답이 하나뿐이라
  IDCG 가 `1/log2(2) = 1` 이고 식이 `1/log2(rank+1)` 로 줄어든다.

두 값을 같이 읽는다. Recall 만 보면 1위로 올린 개선과 10위로 겨우 밀어 넣은 개선이
같아 보이고, nDCG 만 보면 "몇 건이나 아예 못 찾는가"를 못 본다.

## consistency filtering 을 걸지 않는다

`split()` 은 **필터가 아니라 분할선**이다. 지금 top-10 에 원 장면이 안 돌아오는
질의를 정답셋에서 빼면, 측정하려던 실패가 정확히 삭제되어 올라갈 수 없는 셋이 된다
(Promptagator·InPars 가 그 필터를 쓰는 곳은 **학습 데이터**다). 통과군은 회귀 감시용,
실패군은 다음 작업의 입력으로 **둘 다 남긴다**.

## 실패한 호출도 채점한다

`results` 에 해당 문항의 키가 없으면 실패군이다. 응답이 온 문항만 세면 많이 실패한
설정이 좋아 보인다 — `eval/query_resolver/README.md` 가 같은 함정을 적어 뒀다.
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from math import log2
from pathlib import Path
from typing import Any, Final, Literal

#: `gold.json` 이 선언하는 형식. 다른 값이면 로드를 거부한다.
SCHEMA: Final[str] = "search-gold/v1"

#: 한 페이지가 10건이라 화면 하나가 곧 k=10 이다(`docs/contracts/web-api.md` §5.1).
DEFAULT_K: Final[int] = 10

#: 키프레임만 보고 정한 난이도. **결과를 보고 붙이지 않는다.**
Tier = Literal["core", "hard"]
TIERS: Final[tuple[Tier, ...]] = ("core", "hard")


class GoldError(ValueError):
    """골드셋이 규약과 다르다.

    측정 전에 거부한다. 잘못된 라벨은 조용히 "검색이 틀렸다" 로 둔갑한다.
    """


@dataclass(frozen=True, slots=True)
class Case:
    """(질의, 정답 장면) 쌍 하나."""

    id: str
    query: str
    scene_id: str
    clip_id: str
    keyframe: str
    rationale: str
    tier: Tier
    human_reviewed: bool


def load_gold(path: Path) -> tuple[Case, ...]:
    """`gold.json` 을 읽고 기계로 잡을 수 있는 오류를 거부한다."""
    raw: Any = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, Mapping) or raw.get("schema") != SCHEMA:
        msg = f"{SCHEMA} 형식이 아니다: {path}"
        raise GoldError(msg)

    cases: list[Case] = []
    seen: set[str] = set()
    for row in raw.get("cases", ()):
        case_id = str(row["id"])
        if case_id in seen:
            msg = f"같은 id 가 두 번 있다: {case_id}"
            raise GoldError(msg)
        seen.add(case_id)
        query = str(row["query"])
        if not query.strip():
            msg = f"빈 질의가 있다: {case_id}"
            raise GoldError(msg)
        tier = row["tier"]
        if tier not in TIERS:
            msg = f"알 수 없는 tier: {tier!r} ({case_id})"
            raise GoldError(msg)
        cases.append(
            Case(
                id=case_id,
                query=query,
                scene_id=str(row["sceneId"]),
                clip_id=str(row["clipId"]),
                keyframe=str(row["keyframe"]),
                rationale=str(row.get("rationale", "")),
                tier=tier,
                human_reviewed=bool(row.get("humanReviewed", False)),
            )
        )
    if not cases:
        msg = f"케이스가 없다: {path}"
        raise GoldError(msg)
    return tuple(cases)


# ── 지표 ──────────────────────────────────────────────────────────────


def _rank(results: Sequence[str], gold: str, k: int) -> int | None:
    """상위 k 안에서 정답이 처음 나온 1-based 순위. 없으면 None.

    **첫 등장을 쓴다.** 계약상 한 응답에 같은 `scene_id` 는 없지만(§5.1), 그 불변식이
    깨져도 점수를 낙관적으로 보정하지 않는다 — 처음 나온 자리가 사용자가 본 자리다.
    """
    for index, scene_id in enumerate(results[:k], start=1):
        if scene_id == gold:
            return index
    return None


def recall_at_k(results: Sequence[str], gold: str, k: int = DEFAULT_K) -> float:
    """정답 장면이 상위 k 에 있으면 1, 아니면 0."""
    return 1.0 if _rank(results, gold, k) is not None else 0.0


def ndcg_at_k(results: Sequence[str], gold: str, k: int = DEFAULT_K) -> float:
    """이진 관련성·단일 정답의 nDCG. 순위 r 에서 `1/log2(r+1)`, 밖이면 0."""
    rank = _rank(results, gold, k)
    return 0.0 if rank is None else 1.0 / log2(rank + 1)


def split(
    cases: Iterable[Case],
    results: Mapping[str, Sequence[str]],
    k: int = DEFAULT_K,
) -> tuple[tuple[Case, ...], tuple[Case, ...]]:
    """(통과군, 실패군). **필터가 아니라 분할선이다** — 둘 다 남긴다.

    `results` 에 문항 id 가 없으면 실패군이다(호출 실패도 채점한다).
    """
    passed: list[Case] = []
    failed: list[Case] = []
    for case in cases:
        ranked = results.get(case.id)
        target = passed if ranked and recall_at_k(ranked, case.scene_id, k) == 1.0 else failed
        target.append(case)
    return tuple(passed), tuple(failed)


@dataclass(frozen=True, slots=True)
class Scores:
    """한 모집단의 평균. `results/*.json` 에 이 모양으로 쓴다."""

    cases: int
    hits: int
    recall: float
    ndcg: float

    def to_json(self) -> dict[str, Any]:
        return {
            "cases": self.cases,
            "hits": self.hits,
            "recallAt10": round(self.recall, 4),
            "ndcgAt10": round(self.ndcg, 4),
        }


@dataclass(frozen=True, slots=True)
class Summary:
    """전체 평균과 tier 별 평균.

    `Scores` 를 상속하지 않는다 — `slots=True` 프로즌 데이터클래스를 상속하면
    인자 없는 `super()` 가 깨진다. 담는 쪽이 짧다.
    """

    cases: int
    hits: int
    recall: float
    ndcg: float
    by_tier: dict[str, Scores]

    def to_json(self) -> dict[str, Any]:
        return {
            "cases": self.cases,
            "hits": self.hits,
            "recallAt10": round(self.recall, 4),
            "ndcgAt10": round(self.ndcg, 4),
            "byTier": {name: scores.to_json() for name, scores in self.by_tier.items()},
        }


def _scores(cases: Sequence[Case], results: Mapping[str, Sequence[str]], k: int) -> Scores:
    recalls = [recall_at_k(results.get(c.id, ()), c.scene_id, k) for c in cases]
    ndcgs = [ndcg_at_k(results.get(c.id, ()), c.scene_id, k) for c in cases]
    n = len(cases)
    return Scores(
        cases=n,
        hits=int(sum(recalls)),
        recall=sum(recalls) / n if n else 0.0,
        ndcg=sum(ndcgs) / n if n else 0.0,
    )


def summarize(
    cases: Iterable[Case],
    results: Mapping[str, Sequence[str]],
    k: int = DEFAULT_K,
) -> Summary:
    """전체와 tier 별 Recall@k · nDCG@k."""
    rows = tuple(cases)
    overall = _scores(rows, results, k)
    by_tier: dict[str, Scores] = {
        str(tier): _scores([c for c in rows if c.tier == tier], results, k)
        for tier in TIERS
        if any(c.tier == tier for c in rows)
    }
    return Summary(
        cases=overall.cases,
        hits=overall.hits,
        recall=overall.recall,
        ndcg=overall.ndcg,
        by_tier=by_tier,
    )
