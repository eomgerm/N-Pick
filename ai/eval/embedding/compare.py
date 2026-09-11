"""모델 간 차이가 유의한지 판정한다 (S15P21A501-175).

평균만 보면 순위를 말할 수 없다. 2026-09-11 실측에서 `summary` ndcg@10 이
PIXIE 0.9578 / arctic 0.9519 / KURE 0.9463 으로 나왔는데, 질의 300건에서 0.0115 차이는
표준오차와 같은 크기다. "1위"라고 쓰려면 그 차이가 표본 변동보다 큰지 재야 한다.

## 왜 짝지은(paired) 비교인가

모든 모델이 **같은 질의 집합**을 푼다. 질의마다 난이도가 제각각이라 모델 간 분산보다
질의 간 분산이 훨씬 크다. 독립 표본으로 보면 그 공통 분산이 그대로 잡음이 되어 실제
차이를 덮는다. 질의별로 짝지어 차이를 먼저 구하면 질의 난이도가 상쇄된다.

## 방법

질의별 ndcg 차이 d_i = A_i - B_i 를 부트스트랩으로 재표집해 평균 차이의 95% 신뢰구간을
낸다. 구간이 0 을 포함하면 "차이 없음"이다. 함께 승/무/패 질의 수도 보여준다 —
평균은 같은데 한쪽이 크게 이기고 크게 지는 경우를 구분하기 위해서다.

    uv run --group eval python eval/embedding/compare.py \
        eval/embedding/results/hard.json --level summary
"""

from __future__ import annotations

import argparse
import json
import sys
from itertools import combinations
from pathlib import Path

import numpy as np

BOOTSTRAP = 10_000
SEED = 175


def paired_bootstrap(
    a: np.ndarray, b: np.ndarray, n: int = BOOTSTRAP
) -> tuple[float, float, float]:
    """(평균차, 하한, 상한). 질의 인덱스를 재표집하므로 짝이 유지된다."""
    diff = a - b
    rng = np.random.default_rng(SEED)
    idx = rng.integers(0, len(diff), size=(n, len(diff)))
    means = diff[idx].mean(axis=1)
    return float(diff.mean()), float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def load(path: Path, level: str) -> dict[str, np.ndarray]:
    """전체 차원 run 만 쓴다. 잘린 차원은 같은 인코딩에서 파생돼 비교 대상이 아니다."""
    runs = json.loads(path.read_text(encoding="utf-8"))
    out: dict[str, np.ndarray] = {}
    for r in runs:
        if r["params"].get("dim_source") or not r.get("per_query"):
            continue
        rows = [q for q in r["per_query"] if q["level"] == level]
        if not rows:
            continue
        name = r["model"].split("/")[-1]
        out[name] = np.array([q["ndcg"] for q in sorted(rows, key=lambda x: x["i"])])
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description="모델 간 짝지은 유의성 비교")
    parser.add_argument("results", type=Path, nargs="+", help="results.json (여러 개 가능)")
    parser.add_argument("--level", default="summary", help="비교할 질의 레벨")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    merged: dict[str, list[np.ndarray]] = {}
    for path in args.results:
        for name, arr in load(path, args.level).items():
            merged.setdefault(name, []).append(arr)

    # 골드셋이 여러 개면 이어 붙인다. 질의 수가 늘어 구간이 좁아진다.
    scores: dict[str, np.ndarray] = {}
    lengths = {name: sum(len(a) for a in arrs) for name, arrs in merged.items()}
    if len(set(lengths.values())) > 1:
        raise SystemExit(f"모델마다 질의 수가 다르다 — 같은 골드셋인지 확인: {lengths}")
    for name, arrs in merged.items():
        scores[name] = np.concatenate(arrs)

    if len(scores) < 2:
        raise SystemExit(f"level={args.level} 에서 비교할 모델이 2개 미만이다")

    n = len(next(iter(scores.values())))
    print(f"level={args.level} · 질의 {n}건 · 부트스트랩 {BOOTSTRAP}회\n")
    print("평균 ndcg@10")
    for name, arr in sorted(scores.items(), key=lambda kv: -kv[1].mean()):
        print(f"  {name:<36}{arr.mean():.4f}")

    print("\n짝지은 차이 (95% 신뢰구간)")
    for x, y in combinations(sorted(scores, key=lambda k: -scores[k].mean()), 2):
        a, b = scores[x], scores[y]
        mean, lo, hi = paired_bootstrap(a, b)
        wins = int((a > b).sum())
        losses = int((a < b).sum())
        verdict = "유의함" if lo > 0 or hi < 0 else "차이 없음"
        print(f"  {x} vs {y}")
        print(
            f"    {mean:+.4f}  [{lo:+.4f}, {hi:+.4f}]  {verdict}"
            f"   (승 {wins} / 무 {n - wins - losses} / 패 {losses})"
        )


if __name__ == "__main__":
    main()
