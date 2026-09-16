"""Query Resolver 모델 비교 하네스 (S15P21A501-102).

골드셋 200문항을 후보 모델로 돌려 intent accuracy · slot F1 · frame accuracy 를 내고
MLflow 에 기록한다. **워커 런타임이 아니다** — 배포 이미지에 들어가지 않는다. 다만
임베딩 하네스와 달리 `npick_worker.query_resolver` 를 **import 한다**: 실제 프롬프트
(`config.py`)와 실제 검증(`validator.py`)을 거치지 않으면 측정이 무의미하기 때문이다.

    cd ai
    .venv-eval/Scripts/python eval/query_resolver/resolver_bench.py \
        --gold eval/query_resolver/gold.json --out eval/query_resolver/results/latest.json

환경 변수는 `NPICK_AI_GMS_BASE_URL` `NPICK_AI_GMS_API_KEY` 와
`MLFLOW_TRACKING_URI` `MLFLOW_TRACKING_USERNAME` `MLFLOW_TRACKING_PASSWORD` 다.
자세한 것은 README.md.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import statistics
import sys
import threading
import time
from collections.abc import Callable, Mapping, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Final

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "src"))

from metrics import (
    GoldQuery,
    bootstrap_ci,
    frame_hit,
    gold_hash,
    hallucination_hit,
    leak_hit,
    load_gold,
    slot_prf,
    span_corrected_count,
    to_slots,
    to_spans,
)

from npick_worker.query_resolver import (
    QueryResolver,
    QueryResolverConfig,
    ResolutionResult,
    ResolverCallError,
    ResolverSchemaInvalidError,
    get_default_config,
    load_config,
    resolve_query,
)
from npick_worker.query_resolver.config import CallParams
from npick_worker.query_resolver.gms_backend import GmsResolver

RESULTS_DIR: Final[Path] = Path(__file__).resolve().parent / "results"
GOLD_PATH: Final[Path] = Path(__file__).resolve().parent / "gold.json"

#: 실측(2026-09-15)에서 OpenAI 호환 경로로 200 을 주고 temperature=0.0 을 받는 모델.
#: gemini·claude 는 같은 경로에서 404 다 — 별도 upstream 어댑터가 필요하다(README §제외).
CANDIDATES: Final[tuple[str, ...]] = (
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4.1-nano",
    "gpt-4o-mini",
    "gpt-5.4-mini",
    "gpt-5.4-nano",
)

#: 지연 전용 실행에 쓸 문항 수. 도메인마다 고르게 뽑는다.
LATENCY_SAMPLE: Final[int] = 20

_RATE_LIMITED: Final[str] = "RESOLVER_RATE_LIMITED"
_BACKOFF_BASE_S: Final[float] = 1.5
#: gms backend 에 반드시 있어야 하는 환경 변수.
_GMS_ENV: Final[tuple[str, ...]] = ("NPICK_AI_GMS_BASE_URL", "NPICK_AI_GMS_API_KEY")
_MLFLOW_KEY: Final[re.Pattern[str]] = re.compile(r"[^\w.\- /]", re.UNICODE)


@dataclass(frozen=True, slots=True)
class CallOutcome:
    result: ResolutionResult | None
    error: str | None
    seconds: float
    retries: int


@dataclass(frozen=True, slots=True)
class Result:
    model: str
    params: dict[str, Any] = field(default_factory=dict)
    metrics: dict[str, float] = field(default_factory=dict)
    per_query: tuple[dict[str, Any], ...] = ()


# ── 호출 ──────────────────────────────────────────────────────────────


def call_with_backoff(
    make_resolver: Callable[[], QueryResolver],
    query: str,
    config: QueryResolverConfig,
    *,
    max_retry: int = 3,
    sleep: Callable[[float], None] = time.sleep,
) -> CallOutcome:
    """요율 제한에만 지수 백오프로 재시도한다.

    프로덕션은 동기 재시도를 하지 않는다(FRD §6.2). 여기서 예외를 두는 이유는 벤치에서
    429 를 실패로 세면 **요율 제한이 모델 점수로 둔갑하기** 때문이다. timeout·schema
    오류는 재시도하지 않는다 — 그건 그 모델의 진짜 실패율이다.
    """
    started = time.perf_counter()
    retries = 0
    while True:
        try:
            result = resolve_query(query, make_resolver(), config)
        except ResolverCallError as exc:
            if exc.category == _RATE_LIMITED and retries < max_retry:
                retries += 1
                sleep(_BACKOFF_BASE_S**retries)
                continue
            return CallOutcome(
                None, f"{exc.category}: {exc}", time.perf_counter() - started, retries
            )
        except ResolverSchemaInvalidError as exc:
            return CallOutcome(
                None,
                f"RESOLVER_SCHEMA_INVALID: {exc}",
                time.perf_counter() - started,
                retries,
            )
        return CallOutcome(result, None, time.perf_counter() - started, retries)


def gms_factory(model: str, params: CallParams) -> Callable[[], QueryResolver]:
    """스레드마다 별도 인스턴스를 준다.

    `GmsResolver` 는 응답의 `model` 을 `self._reported_model` 에 적는다(gms_backend.py).
    인스턴스를 공유하면 그 필드에 경합이 생겨 `model_version` 기록이 섞인다.
    """
    local = threading.local()
    missing = [name for name in _GMS_ENV if not os.environ.get(name)]
    if missing:
        msg = f"{', '.join(missing)} 이 비어 있다. 환경 변수로 지정한다 (README 참조)"
        raise ValueError(msg)
    base_url = os.environ["NPICK_AI_GMS_BASE_URL"]
    api_key = os.environ["NPICK_AI_GMS_API_KEY"]

    def factory() -> QueryResolver:
        resolver: QueryResolver | None = getattr(local, "resolver", None)
        if resolver is None:
            resolver = GmsResolver(base_url, api_key, model, params)
            local.resolver = resolver
        return resolver

    return factory


# ── 채점 ──────────────────────────────────────────────────────────────


def _slug(text: str) -> str:
    """MLflow metric key 로 쓸 수 있게 만든다. `·` 같은 문자가 거부된다."""
    return _MLFLOW_KEY.sub("_", text)


def score_row(
    g: GoldQuery,
    *,
    resolution: Mapping[str, Any] | None,
    finding_lines: Sequence[str],
    seconds: float,
    retries: int,
    error: str | None,
) -> dict[str, Any]:
    """질의 하나를 채점한다. 호출 결과와 저장된 결과가 **같은 함수**를 지난다.

    실패한 호출도 채점한다 — 살아남은 응답만 세면 많이 실패한 모델이 좋아 보인다.

    `finding_lines` 는 `AnchorFinding` 을 사람이 읽는 형식으로 적은 줄이다. action 이
    그 안에 들어 있어 `in` 검사로 판정한다. 구조를 따로 저장하지 않는 이유는 이 줄이
    결과 파일에 이미 남고, 두 벌을 두면 한쪽만 바뀌기 때문이다.
    """
    row: dict[str, Any] = {
        "id": g.id,
        "domain": g.domain,
        "query": g.query,
        "sec": round(seconds, 3),
        "retries": retries,
        "ok": resolution is not None,
    }
    if resolution is None:
        row.update(
            error=error,
            intent_hit=False,
            slot_p=0.0,
            slot_r=0.0,
            slot_f1=0.0,
            frame_hit=False,
            leak=False,
            hallucination=False,
            tp_p=0,
            tp_r=0,
            n_pred=0,
            n_gold=len(g.slots),
            span_total=0,
            span_corrected=0,
        )
        return row

    pred = to_slots(resolution)
    precision, recall, f1 = slot_prf(pred, g.slots, g.optional)

    row.update(
        error=None,
        intent=resolution["intent"],
        intent_hit=resolution["intent"] == g.intent,
        slot_p=round(precision, 4),
        slot_r=round(recall, 4),
        slot_f1=round(f1, 4),
        frame_hit=frame_hit(resolution["intent"], pred, g),
        leak=leak_hit(pred, g.forbidden),
        hallucination=hallucination_hit(finding_lines),
        # precision 과 recall 의 분자가 다르다. precision 은 허용된 것(필수+선택)을 맞다고
        # 보지만 recall 의 분모는 필수 슬롯뿐이라, 같은 분자를 쓰면 optional 을 낸 모델에서
        # recall 이 1 을 넘고 F1 이 1.027 같은 값이 된다.
        tp_p=len(pred & (g.slots | g.optional)),
        tp_r=len(pred & g.slots),
        n_pred=len(pred),
        n_gold=len(g.slots),
        # span 정확도는 validator 가 몇 개를 고쳤는지로 잰다. 결과의 span 을 gold 와 비교하면
        # 이미 고쳐진 값을 보게 되어 모델이 아니라 validator 를 재게 된다.
        span_total=len(to_spans(resolution)),
        span_corrected=span_corrected_count(finding_lines),
        resolution=resolution,
        findings=list(finding_lines),
    )
    return row


def _score_one(outcome: CallOutcome, g: GoldQuery) -> dict[str, Any]:
    """`CallOutcome` 을 `score_row` 입력으로 옮긴다."""
    result = outcome.result
    return score_row(
        g,
        resolution=None if result is None else result.resolution.model_dump(mode="json"),
        finding_lines=(
            () if result is None else [f"{f.path} {f.action}: {f.reason}" for f in result.findings]
        ),
        seconds=outcome.seconds,
        retries=outcome.retries,
        error=outcome.error,
    )


def _aggregate(rows: Sequence[dict[str, Any]], *, concurrent: bool) -> dict[str, float]:
    total = len(rows)
    lat = sorted(r["sec"] * 1000 for r in rows)
    suffix = "_concurrent" if concurrent else ""
    tp_p = sum(r["tp_p"] for r in rows)
    tp_r = sum(r["tp_r"] for r in rows)
    n_pred = sum(r["n_pred"] for r in rows)
    n_gold = sum(r["n_gold"] for r in rows)
    micro_p = tp_p / n_pred if n_pred else 1.0
    micro_r = tp_r / n_gold if n_gold else 1.0
    micro_f1 = 2 * micro_p * micro_r / (micro_p + micro_r) if micro_p + micro_r else 0.0
    anchored = [r["slot_f1"] for r in rows if r["n_gold"]]
    unanchored = [float(r["frame_hit"]) for r in rows if not r["n_gold"]]
    span_total = sum(r["span_total"] for r in rows)
    span_corrected = sum(r["span_corrected"] for r in rows)

    metrics = {
        "schema_valid_rate": sum(r["ok"] for r in rows) / total,
        "intent_accuracy": sum(r["intent_hit"] for r in rows) / total,
        "slot_precision": micro_p,
        "slot_recall": micro_r,
        "slot_f1": micro_f1,
        "slot_f1_macro": statistics.fmean(r["slot_f1"] for r in rows),
        # 빈-gold 문항은 아무것도 안 내면 F1 1.0 이다. 그래서 위 두 지표에는 "덜 내는
        # 모델"에게 주는 공짜 점수가 섞여 있다 — 실측으로 null 모델이 slot_f1_macro
        # 0.225, frame_accuracy 0.13 을 받는다. 아래 둘은 그 둘을 분리해서 본다.
        "slot_f1_anchored": statistics.fmean(anchored) if anchored else 1.0,
        "suppression_accuracy": statistics.fmean(unanchored) if unanchored else 1.0,
        "anchored_queries": float(len(anchored)),
        "unanchored_queries": float(len(unanchored)),
        "frame_accuracy": sum(r["frame_hit"] for r in rows) / total,
        "leak_rate": sum(r["leak"] for r in rows) / total,
        "hallucination_rate": sum(r["hallucination"] for r in rows) / total,
        "span_exact_rate": (span_total - span_corrected) / span_total if span_total else 1.0,
        "rate_limited_count": float(sum(r["retries"] for r in rows)),
        f"latency_p50_ms{suffix}": statistics.median(lat),
        f"latency_p95_ms{suffix}": lat[min(int(len(lat) * 0.95), len(lat) - 1)],
        f"latency_stdev_ms{suffix}": statistics.stdev(lat) if len(lat) > 1 else 0.0,
    }

    by_domain: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        by_domain.setdefault(row["domain"], []).append(row)
    for domain, group in by_domain.items():
        metrics[f"slot_f1_dom_{_slug(domain)}"] = statistics.fmean(r["slot_f1"] for r in group)
    return {k: round(float(v), 6) for k, v in metrics.items()}


def run_model(
    model: str,
    gold: Sequence[GoldQuery],
    config: QueryResolverConfig,
    *,
    timeout_s: float,
    concurrency: int,
    resolver_factory: Callable[[], QueryResolver] | None = None,
) -> Result:
    params = CallParams(
        temperature=config.call.temperature,
        max_output_tokens=config.call.max_output_tokens,
        timeout_seconds=timeout_s,
    )
    factory = resolver_factory if resolver_factory is not None else gms_factory(model, params)

    def work(g: GoldQuery) -> dict[str, Any]:
        return _score_one(call_with_backoff(factory, g.query, config), g)

    if concurrency > 1:
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            rows = list(pool.map(work, gold))
    else:
        rows = [work(g) for g in gold]

    return Result(
        model=model,
        params={
            "model": model,
            "backend": "gms",
            "prompt_version": config.prompt_version,
            "gold_hash": gold_hash(gold),
            "gold_size": len(gold),
            "temperature": config.call.temperature,
            "max_output_tokens": config.call.max_output_tokens,
            "timeout_seconds": timeout_s,
            "concurrency": concurrency,
            "json_mode": True,
            "host_python": platform.python_version(),
            "host_platform": platform.platform(),
        },
        metrics=_aggregate(rows, concurrent=concurrency > 1),
        per_query=tuple(rows),
    )


def rescore(results_path: Path, gold: Sequence[GoldQuery]) -> list[Result]:
    """저장된 결과를 **API 호출 없이** 다시 채점한다.

    지표 정의가 바뀔 때마다 200문항 x 6모델을 다시 부르면 호출당 크레딧이 든다. 질의별
    행에 해석 결과 전문과 validator finding 이 남아 있으므로 같은 `score_row` 를 다시
    돌리면 된다. 지연은 그때 잰 값을 그대로 쓴다 — 재집계로는 다시 잴 수 없다.
    """
    stored = json.loads(results_path.read_text(encoding="utf-8"))
    by_id = {g.id: g for g in gold}
    out: list[Result] = []
    for entry in stored:
        rows: list[dict[str, Any]] = []
        for old_row in entry["per_query"]:
            g = by_id.get(old_row["id"])
            if g is None:
                # --only-domain 으로 골드셋이 걸러진 경우다. 겹치는 질의만 다시 채점한다.
                continue
            rows.append(
                score_row(
                    g,
                    resolution=old_row.get("resolution"),
                    finding_lines=old_row.get("findings") or (),
                    seconds=old_row["sec"],
                    retries=old_row["retries"],
                    error=old_row.get("error"),
                )
            )
        if not rows:
            msg = f"{entry['model']}: 결과와 골드셋에 겹치는 질의가 없다. 다른 골드셋으로 잰 결과다"
            raise ValueError(msg)
        params = dict(entry["params"])
        params["rescored_from"] = str(results_path)
        out.append(
            Result(
                model=entry["model"],
                params=params,
                metrics=_aggregate(rows, concurrent=int(params.get("concurrency", 1)) > 1),
                per_query=tuple(rows),
            )
        )
    return out


# ── 비교 ──────────────────────────────────────────────────────────────


def compare(results: Sequence[Result], per_query_key: str = "slot_f1") -> str:
    """1위 모델과 나머지의 짝지은 차이에 95% 부트스트랩 신뢰구간을 붙인다.

    **순위와 검정에 같은 값을 쓴다.** 전에는 순위를 집계 지표(micro)로 매기고 차이는
    질의별 값(macro)으로 재서, 표에 적힌 기준값과 CI 가 다른 지표를 말했다
    (실측: micro 차이 0.0225 인데 CI 는 macro 차이 0.0004 에 붙었다).
    """
    if len(results) < 2:
        return ""

    def mean_of(result: Result) -> float:
        values = [r.get(per_query_key, 0.0) for r in result.per_query]
        return statistics.fmean(values) if values else 0.0

    ranked = sorted(results, key=mean_of, reverse=True)
    best = ranked[0]
    lines = [f"\n기준 {best.model} ({per_query_key} 질의별 평균 {mean_of(best):.4f}) 대비 차이"]
    base = {r["id"]: r.get(per_query_key, 0.0) for r in best.per_query}
    for other in ranked[1:]:
        diffs = [
            base[r["id"]] - r.get(per_query_key, 0.0) for r in other.per_query if r["id"] in base
        ]
        lo, hi = bootstrap_ci(diffs)
        verdict = "유의" if lo > 0 else "이 표본으로는 검출 못 함"
        mean = statistics.fmean(diffs) if diffs else 0.0
        lines.append(
            f"  vs {other.model:<14} 차이={mean:+.4f}  95% CI [{lo:+.4f}, {hi:+.4f}]  {verdict}"
        )
    return "\n".join(lines)


def print_table(results: Sequence[Result]) -> None:
    keys = (
        "schema_valid_rate",
        "intent_accuracy",
        "slot_f1",
        "frame_accuracy",
        "hallucination_rate",
        "leak_rate",
        "span_exact_rate",
    )
    print(f"\n{'model':<14}" + "".join(f"{k.replace('_', ' ')[:11]:>13}" for k in keys))
    for r in sorted(results, key=lambda x: x.metrics.get("slot_f1", 0.0), reverse=True):
        print(f"{r.model:<14}" + "".join(f"{r.metrics.get(k, 0.0):>13.4f}" for k in keys))


def dump(results: Sequence[Result], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(
            [
                {
                    "model": r.model,
                    "params": r.params,
                    "metrics": r.metrics,
                    "per_query": list(r.per_query),
                }
                for r in results
            ],
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def latency_subset(gold: Sequence[GoldQuery], size: int = LATENCY_SAMPLE) -> tuple[GoldQuery, ...]:
    """도메인마다 고르게 뽑는다. 한 도메인의 긴 질의가 지연을 대표하면 안 된다."""
    by_domain: dict[str, list[GoldQuery]] = {}
    for g in sorted(gold, key=lambda x: x.id):
        by_domain.setdefault(g.domain, []).append(g)
    picked: list[GoldQuery] = []
    round_index = 0
    while len(picked) < size and any(len(v) > round_index for v in by_domain.values()):
        for group in by_domain.values():
            if len(picked) >= size:
                break
            if len(group) > round_index:
                picked.append(group[round_index])
        round_index += 1
    return tuple(picked[:size])


def main() -> None:
    parser = argparse.ArgumentParser(description="Query Resolver 모델 비교 (S15P21A501-102)")
    parser.add_argument("--gold", type=Path, default=GOLD_PATH)
    parser.add_argument("--config", type=Path, default=None, help="프롬프트 toml. 기본은 동봉본")
    parser.add_argument("--models", nargs="*", default=list(CANDIDATES))
    parser.add_argument("--only-domain", default=None, help="이 도메인만 돌린다")
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=30.0, help="측정용. 운영값은 8초")
    parser.add_argument(
        "--latency-only",
        action="store_true",
        help=f"도메인별 {LATENCY_SAMPLE}문항을 직렬로 돌려 지연만 잰다",
    )
    parser.add_argument("--out", type=Path, default=RESULTS_DIR / "latest.json")
    parser.add_argument(
        "--rescore",
        type=Path,
        default=None,
        help="저장된 결과를 API 호출 없이 다시 채점한다. 지표 정의를 바꿨을 때 쓴다",
    )
    parser.add_argument("--experiment", default="query-resolver-eval-v1")
    parser.add_argument(
        "--tracking-uri", default=os.environ.get("MLFLOW_TRACKING_URI", "http://127.0.0.1:5000")
    )
    parser.add_argument("--no-mlflow", action="store_true", help="콘솔·JSON 출력만")
    args = parser.parse_args()

    # Windows 기본 콘솔이 cp949 라 한글 출력에서 죽는다. 측정 결과를 잃을 이유가 없다.
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="replace")

    config = load_config(args.config) if args.config else get_default_config()
    gold = load_gold(args.gold)
    if args.only_domain:
        gold = tuple(g for g in gold if g.domain == args.only_domain)
    concurrency = 1 if args.latency_only else max(1, args.concurrency)
    if args.latency_only:
        gold = latency_subset(gold)

    print(
        f"prompt {config.prompt_version} | gold {len(gold)}문항 {gold_hash(gold)} "
        f"| 동시성 {concurrency}"
    )

    mlflow = None
    if not args.no_mlflow:
        import mlflow as _mlflow

        mlflow = _mlflow
        mlflow.set_tracking_uri(args.tracking_uri)
        mlflow.set_experiment(args.experiment)

    results: list[Result] = []
    if args.rescore is not None:
        results = rescore(args.rescore, gold)
        print(f"재집계: {args.rescore} (API 호출 없음)")
        for result in results:
            print()
            print("=== " + result.model + " ===")
            for key, value in result.metrics.items():
                if not key.startswith("slot_f1_dom_"):
                    print(f"  {key:<26} {value}")
            if mlflow:
                with mlflow.start_run(run_name=f"resolver-{result.model}-rescored"):
                    mlflow.log_params(result.params)
                    mlflow.log_metrics(result.metrics)
                    mlflow.set_tags({"ticket": "S15P21A501-102", "mode": "rescore"})
        dump(results, args.out)
    else:
        for model in args.models:
            started = time.perf_counter()
            print()
            print("=== " + model + " ===", flush=True)
            result = run_model(model, gold, config, timeout_s=args.timeout, concurrency=concurrency)
            results.append(result)
            for key, value in result.metrics.items():
                if not key.startswith("slot_f1_dom_"):
                    print(f"  {key:<26} {value}")
            print(f"  ({time.perf_counter() - started:.1f}s)", flush=True)

            if mlflow:
                suffix = "-latency" if args.latency_only else ""
                with mlflow.start_run(run_name=f"resolver-{model}{suffix}"):
                    mlflow.log_params(result.params)
                    mlflow.log_metrics(result.metrics)
                    mlflow.set_tags(
                        {
                            "ticket": "S15P21A501-102",
                            "mode": "latency" if args.latency_only else "score",
                        }
                    )

            # 모델 하나 끝날 때마다 덮어쓴다. 뒤 모델이 죽어도 앞의 질의별 점수는 남는다.
            dump(results, args.out)

    print_table(results)
    print(compare(results))
    dump(results, args.out)
    print(f"\n→ {args.out}")
    if mlflow:
        print(f"→ MLflow {args.tracking_uri} / experiment={args.experiment}")


if __name__ == "__main__":
    main()
