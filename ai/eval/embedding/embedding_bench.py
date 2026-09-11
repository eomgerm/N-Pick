"""임베딩 모델 비교 — S15P21A501-175.

워커 런타임이 아니다. `eval` 그룹에서만 돌고 `src/npick_worker` 를 import 하지 않는다
(fixture 경로 하나만 읽는다). 배포 이미지에 들어가지 않는다.

측정 두 갈래:

- **라벨 없이 지금 되는 것** — 벡터 차원·질의 접두어·모델 리비전·CPU 질의 지연·
  GPU 색인 처리량·peak VRAM. 이게 -175 의 "지연·VRAM·벡터 차원 측정해 비교표" 다.
- **골드셋이 있어야 되는 것** — recall@10·ndcg@10·mrr. `--gold` 로 파일을 주면 붙는다.
  없으면 건너뛴다. 라벨을 지어내지 않는다 (FRD §8.3: 대표 질의는 정답지가 아니다).

질의 지연이 모델 크기 상한을 정한다 — 색인은 GPU 지만 질의는 EC2 4 vCPU CPU 동기다
(03-deployment). 그래서 CPU 스레드를 4로 못 박고 잰다. 안 그러면 개발 노트북 코어 수만큼
낙관적인 숫자가 나온다.

    uv run --group eval python eval/embedding/embedding_bench.py --self-check
    uv run --group eval python eval/embedding/embedding_bench.py --gold eval/embedding/gold.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import statistics
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

BENCH_DIR = Path(__file__).resolve().parent  # ai/eval/embedding
RESULTS_DIR = BENCH_DIR / "results"
AI_DIR = BENCH_DIR.parent.parent  # ai
# 리졸버 fixture 의 대표 질의 20건. 편집기자가 실제로 치는 자연어 문장이라
# 질의 지연의 토큰 길이 분포가 현실적이다. 정답지가 아니므로 채점에는 쓰지 않는다.
QUERY_FIXTURE = AI_DIR / "src/npick_worker/query_resolver/fixtures/representative_queries.v2.json"

# EC2 4 vCPU (03-deployment). 노트북 코어를 다 쓰면 배포 환경보다 빠른 수치가 나온다.
CPU_THREADS = 4
TOP_K = 10


@dataclass(frozen=True)
class ModelSpec:
    """후보 1종. `query_prefix` 는 모델 카드가 prompts 를 선언하지 않을 때의 대비책이다."""

    repo: str
    query_prefix: str = ""
    note: str = ""


# 공식 MTEB(kor, v2) Retrieval 상위 + ko-embedding-leaderboard 상위 교집합에서
# 0.6B 급·1024차원·오픈웨이트만. 선정 근거는 Notion "한국어 임베딩 모델 선정 조사".
CANDIDATES = [
    ModelSpec("nlpai-lab/KURE-v1", note="MIT. 접두어 불필요 — 색인/질의 어긋날 자리가 없다"),
    ModelSpec("dragonkue/snowflake-arctic-embed-l-v2.0-ko", query_prefix="query: "),
    ModelSpec("telepix/PIXIE-Rune-v1.5", query_prefix="query: "),
]

# 지연·처리량 전용 합성 장면 텍스트. 캡션 + 대사 형태이고 OCR 은 넣지 않는다(FRD §11).
# **라벨이 아니다** — 길이 분포만 현실적이면 되는 자리라서 내용의 진위는 무관하다.
SCENE_TEXTS = [
    "고속도로 상행선에 차량이 빽빽하게 늘어서 있다. 갓길까지 차가 들어찼다. "
    "귀성길에 오른 시민들은 예년보다 정체가 심하다고 말했다.",
    "서울역 대합실이 귀성객으로 가득 찼다. 캐리어를 끌고 이동하는 인파가 이어진다. "
    "승강장 안내 방송이 반복해서 나오고 있다.",
    "앵커가 스튜디오에서 정면을 보고 리포트를 소개한다. 뒤쪽 스크린에 교통 상황 그래픽이 떠 있다.",
    "폭우가 내리는 도심 사거리. 우산을 쓴 보행자들이 횡단보도를 건넌다. 노면에 물이 고여 있다.",
    "재래시장 좌판에 비닐이 덮여 있다. 상인이 빗물을 쓸어내며 손님을 기다린다. "
    "오늘 장사는 글렀다고 말한다.",
    "공항 출국장에 여행객 줄이 길게 늘어서 있다. 전광판에 항공편 정보가 흐른다.",
    "취재진 앞에서 의원이 마이크를 잡고 발언한다. 플래시가 연이어 터진다.",
    "한국도로공사 관계자가 브리핑 자료를 넘기며 설명한다. 뒤 배경에 기관 로고가 보인다.",
    "눈 덮인 역 광장을 시민들이 종종걸음으로 지나간다. 입김이 하얗게 보인다.",
    "사고 현장에 구조대원들이 모여 있다. 통제선 밖으로 시민들이 지켜보고 있다.",
    "경기장 관중석이 관중으로 가득하다. 응원 깃발이 물결친다.",
    "바이애슬론 경기 안내판 앞을 관람객이 지나간다. 행사 배너가 바람에 흔들린다.",
    "기자가 현장에서 마이크를 들고 상황을 전한다. 뒤로 소방차 불빛이 번쩍인다.",
    "지하철 개찰구를 통과하는 시민들의 발걸음이 빠르다. 출근 시간대 혼잡이 이어진다.",
    "논밭 위로 드론 시점의 화면이 펼쳐진다. 수확을 앞둔 벼가 노랗게 익었다.",
    "아파트 단지 앞 도로가 침수돼 차량이 반쯤 잠겼다. 주민들이 발을 동동 구른다.",
    "카메라가 광장을 천천히 훑는다. 추모객들이 헌화하고 묵념한다.",
    "회의실에서 참석자들이 자료를 넘기며 논의한다. 정면 스크린에 도표가 떠 있다.",
    "해변에 파도가 높게 인다. 방파제 너머로 물보라가 넘어온다.",
    "야간 도심 도로에 차량 불빛이 길게 이어진다. 빌딩 조명이 켜져 있다.",
    "공사 현장에서 크레인이 자재를 들어 올린다. 안전모를 쓴 작업자들이 신호를 주고받는다.",
    "병원 로비에서 환자와 보호자가 순서를 기다린다. 접수 창구에 안내 문구가 붙어 있다.",
    "학교 운동장에서 학생들이 줄을 맞춰 서 있다. 확성기로 안내가 나온다.",
    "터미널 매표소 앞에 승객들이 줄지어 서 있다. 전광판에 출발 시각이 표시된다.",
]
# FRD §8.3 의 목표 규모. 색인 처리량을 실제 코퍼스 크기로 재려고 맞춘다.
INDEX_CORPUS_SIZE = 2_200


# ── 검색 지표 ────────────────────────────────────────────────────────
# 골드셋이 있을 때만 쓴다. --self-check 가 이 셋을 검증한다.


def recall_at_k(ranked: list[int], relevant: set[int], k: int) -> float:
    """상위 k 안에 들어온 정답 비율. 정답이 없으면 정의되지 않으므로 호출부가 거른다.

    정답이 k 보다 많으면 상한이 k/|R| 로 눌린다(정답 245건이면 recall@10 최대 0.04).
    그래서 category 레벨은 precision 을 함께 본다.
    """
    return len(set(ranked[:k]) & relevant) / len(relevant)


def precision_at_k(ranked: list[int], relevant: set[int], k: int) -> float:
    """상위 k 중 정답 비율. 정답이 k 보다 많은 질의에서 recall 대신 읽는 축이다."""
    return len(set(ranked[:k]) & relevant) / k


def ndcg_at_k(ranked: list[int], relevant: set[int], k: int) -> float:
    """이진 관련도 nDCG. 정답 수가 k 보다 많을 수 있어 IDCG 는 min(|R|, k) 로 자른다."""
    dcg = sum(1.0 / np.log2(i + 2) for i, doc in enumerate(ranked[:k]) if doc in relevant)
    idcg = sum(1.0 / np.log2(i + 2) for i in range(min(len(relevant), k)))
    return dcg / idcg if idcg else 0.0


def reciprocal_rank(ranked: list[int], relevant: set[int]) -> float:
    """첫 정답의 역순위. 상위 구간에 정답이 하나도 없으면 0."""
    for i, doc in enumerate(ranked):
        if doc in relevant:
            return 1.0 / (i + 1)
    return 0.0


def self_check() -> None:
    """지표 3종을 손으로 계산한 값과 맞춘다. 모델도 GPU 도 필요 없다."""
    ranked = [7, 3, 1, 9, 2]
    relevant = {3, 2}

    assert recall_at_k(ranked, relevant, 10) == 1.0
    assert recall_at_k(ranked, relevant, 2) == 0.5  # 상위 2개 중 3만 정답
    assert recall_at_k(ranked, relevant, 1) == 0.0

    assert precision_at_k(ranked, relevant, 2) == 0.5  # 2개 중 1개
    assert precision_at_k(ranked, relevant, 10) == 0.2  # 10칸 중 2개 — 분모는 k 로 고정
    # 정답이 k 보다 많으면 recall 은 눌리고 precision 은 살아 있다
    many = set(range(100))
    assert recall_at_k(list(range(100)), many, 10) == 0.1
    assert precision_at_k(list(range(100)), many, 10) == 1.0

    # 정답이 2위·5위 → DCG = 1/log2(3) + 1/log2(6), IDCG = 1/log2(2) + 1/log2(3)
    expected = (1 / np.log2(3) + 1 / np.log2(6)) / (1 / np.log2(2) + 1 / np.log2(3))
    assert abs(ndcg_at_k(ranked, relevant, 10) - expected) < 1e-9
    # 완벽한 순위는 1.0
    assert abs(ndcg_at_k([3, 2, 7], relevant, 10) - 1.0) < 1e-9
    # 정답이 하나도 안 들어오면 0
    assert ndcg_at_k([7, 1, 9], relevant, 10) == 0.0

    assert reciprocal_rank(ranked, relevant) == 0.5  # 3 이 2위
    assert reciprocal_rank([7, 1, 9], relevant) == 0.0

    # truncate: 자른 뒤 단위 벡터여야 한다. 재정규화를 빼면 여기서 걸린다.
    v = np.array([[3.0, 4.0, 12.0], [1.0, 0.0, 0.0]])
    v = v / np.linalg.norm(v, axis=1, keepdims=True)
    cut = truncate(v, 2)
    assert cut.shape == (2, 2)
    assert np.allclose(np.linalg.norm(cut, axis=1), 1.0)
    assert np.allclose(cut[0], [0.6, 0.8])  # 3,4 를 살린 뒤 정규화 -> 3-4-5 삼각형
    # 0 벡터가 되는 경우에도 나눗셈이 터지지 않아야 한다
    assert np.all(np.isfinite(truncate(np.array([[0.0, 0.0, 1.0]]), 2)))

    print("self-check OK — recall/ndcg/mrr + truncate 통과")


# ── 측정 ─────────────────────────────────────────────────────────────


@dataclass
class Result:
    model: str
    params: dict[str, Any] = field(default_factory=dict)
    metrics: dict[str, float] = field(default_factory=dict)
    # 질의별 ndcg@10. 모델 간 짝지은 비교(compare.py)용이며 MLflow 에는 싣지 않는다.
    per_query: list[dict[str, Any]] = field(default_factory=list)


def _percentile(values: list[float], pct: float) -> float:
    return float(np.percentile(np.asarray(values), pct))


def _resolve_prefix(model: Any, spec: ModelSpec) -> tuple[str, str]:
    """모델 카드가 선언한 query prompt 를 우선한다. 없으면 spec 의 리터럴.

    접두어 비대칭이 이 결정의 최대 운영 리스크다 — 색인(-100)과 질의(-164)가 다른
    티켓이라 어긋나면 오류 없이 품질만 떨어진다. 그래서 무엇을 썼는지 params 로 남긴다.
    """
    prompts = getattr(model, "prompts", None) or {}
    for key in ("query", "s2p_query", "retrieval.query"):
        if key in prompts:
            return prompts[key], f"model_card:{key}"
    return spec.query_prefix, "spec_literal" if spec.query_prefix else "none"


def _revision(repo: str) -> str:
    """태그가 아니라 커밋 해시를 남긴다. 태그는 조용히 움직인다."""
    try:
        from huggingface_hub import HfApi

        return HfApi().model_info(repo).sha or "unknown"
    except Exception:  # 오프라인·권한 등 — 측정 자체를 막을 이유는 아니다
        return "unknown"


def benchmark(
    spec: ModelSpec,
    queries: list[str],
    gold: dict | None,
    dims: list[int] | None = None,
    dtype: str | None = None,
) -> list[Result]:
    import torch
    from sentence_transformers import SentenceTransformer

    torch.set_num_threads(CPU_THREADS)
    dims = dims or []

    result = Result(model=spec.repo)

    # dtype 을 안 주면 모델 카드의 torch_dtype 이 쓰인다 — 그러면 모델마다 달라져서
    # 지연·VRAM 비교가 공정하지 않다(2026-09-11: Qwen 만 bf16, 나머지 fp32 로 로드돼
    # Qwen 이 VRAM 38% 적게 나왔다). 비교할 때는 명시적으로 하나로 맞춘다.
    kwargs: dict[str, Any] = {"device": "cpu", "trust_remote_code": True}
    if dtype:
        kwargs["model_kwargs"] = {"torch_dtype": getattr(torch, dtype)}

    t0 = time.perf_counter()
    model = SentenceTransformer(spec.repo, **kwargs)
    result.metrics["load_s"] = round(time.perf_counter() - t0, 2)

    prefix, prefix_source = _resolve_prefix(model, spec)
    dim = model.get_sentence_embedding_dimension() or 0
    n_params = sum(p.numel() for p in model.parameters())

    result.params = {
        "model": spec.repo,
        "model_revision": _revision(spec.repo),
        "embedding_dim": dim,
        "max_seq_length": model.max_seq_length,
        "params_m": round(n_params / 1e6, 1),
        "query_prefix": repr(prefix),
        "query_prefix_source": prefix_source,
        "cpu_threads": CPU_THREADS,
        "torch_dtype": dtype or "model_card_default",
        "top_k": TOP_K,
    }

    # ── CPU 단건 질의 지연. 질의 경로가 모델 크기 상한을 정한다 ──
    warmup = queries[:2]
    model.encode([prefix + q for q in warmup], batch_size=1, show_progress_bar=False)

    latencies: list[float] = []
    for _ in range(3):  # 질의 20건 x 3회 = 60 표본
        for q in queries:
            t = time.perf_counter()
            model.encode(prefix + q, show_progress_bar=False)
            latencies.append((time.perf_counter() - t) * 1000)

    result.metrics["query_embed_p50_ms"] = round(statistics.median(latencies), 1)
    result.metrics["query_embed_p95_ms"] = round(_percentile(latencies, 95), 1)
    result.metrics["query_embed_max_ms"] = round(max(latencies), 1)

    # ── GPU 색인 처리량·peak VRAM ──
    if torch.cuda.is_available():
        model.to("cuda")
        corpus = [SCENE_TEXTS[i % len(SCENE_TEXTS)] for i in range(INDEX_CORPUS_SIZE)]
        model.encode(corpus[:64], batch_size=32, show_progress_bar=False)  # warmup
        torch.cuda.synchronize()
        torch.cuda.reset_peak_memory_stats()

        t = time.perf_counter()
        model.encode(corpus, batch_size=32, show_progress_bar=False)
        torch.cuda.synchronize()
        elapsed = time.perf_counter() - t

        result.metrics["index_texts_per_s"] = round(INDEX_CORPUS_SIZE / elapsed, 1)
        result.metrics["index_2200_scenes_s"] = round(elapsed, 1)
        result.metrics["peak_vram_mb"] = round(torch.cuda.max_memory_allocated() / 1e6, 1)

    # ── 골드셋이 있을 때만 검색 품질 ──
    # GPU 가 있으면 올라간 채로 채점한다. 코퍼스 5천 건을 CPU 4스레드로 인코딩하면
    # 모델당 10분씩 걸린다(2026-09-11 실측). 채점은 품질 측정이지 지연 측정이 아니라
    # 디바이스가 숫자를 바꾸지 않는다 — CPU 로 재는 것은 위의 질의 지연뿐이다.
    results = [result]
    if gold:
        doc_vecs, q_vecs, index = _encode_gold(model, prefix, gold)
        pq: list[dict[str, Any]] = []
        result.metrics.update(_score_vecs(doc_vecs, q_vecs, gold, index, pq))
        result.per_query = pq

        # 차원 스윕. 인코딩은 위에서 한 번만 하고 잘라서 재채점한다.
        # 지연·VRAM 은 전체 차원에서 잰 값을 그대로 물려준다 — 모델은 어차피 전체
        # 차원을 계산한 뒤 자르므로 차원을 줄여도 연산량이 줄지 않는다.
        for d in sorted(x for x in dims if x < dim):
            trimmed = Result(
                model=spec.repo,
                params={**result.params, "embedding_dim": d, "dim_source": f"truncate_from_{dim}"},
            )
            trimmed.metrics = {
                k: v
                for k, v in result.metrics.items()
                if not k.endswith("_at_10") and "mrr" not in k
            }
            trimmed.metrics.update(
                _score_vecs(truncate(doc_vecs, d), truncate(q_vecs, d), gold, index)
            )
            results.append(trimmed)

    if torch.cuda.is_available():
        model.to("cpu")
        torch.cuda.empty_cache()

    del model
    return results


def _encode_gold(model: Any, prefix: str, gold: dict) -> tuple[Any, Any, dict[str, int]]:
    """골드셋을 전체 차원으로 한 번만 인코딩한다. 차원 스윕은 이 결과를 잘라 쓴다."""
    scenes = gold["scenes"]
    index = {s["id"]: i for i, s in enumerate(scenes)}
    doc_vecs = model.encode(
        [s["text"] for s in scenes],
        batch_size=32,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    q_vecs = model.encode(
        [prefix + q["query"] for q in gold["queries"]],
        batch_size=32,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    return doc_vecs, q_vecs, index


def truncate(vecs: Any, dim: int) -> Any:
    """앞 dim 차원만 남기고 **다시 L2 정규화**한다.

    정규화된 벡터의 앞부분은 그 자체로 정규화돼 있지 않다. 코사인 유사도는 단위 벡터를
    전제하므로 재정규화를 빼면 잘린 차원에서 점수가 틀어진다 — MRL 평가의 표준 절차다.
    모델이 MRL 로 학습되지 않았으면 이 절차를 지켜도 품질이 무너진다. 그 무너짐 자체가
    "이 모델은 자를 수 없다"는 측정 결과다.
    """
    cut = vecs[:, :dim]
    norms = np.linalg.norm(cut, axis=1, keepdims=True)
    return cut / np.maximum(norms, 1e-12)


def _score_vecs(
    doc_vecs: Any,
    q_vecs: Any,
    gold: dict,
    index: dict[str, int],
    per_query: list[dict[str, Any]] | None = None,
) -> dict[str, float]:
    """골드셋으로 recall@10·precision@10·ndcg@10·mrr.

    레벨별로 갈라서 낸다 — 레벨마다 재는 것이 다르고 정답 수도 1건~245건으로 벌어져서
    전체 평균 하나로 묶으면 아무 것도 말하지 못한다. 전체 평균은 `all_` 로 함께 남긴다.
    """
    # 상위 K 만 필요하므로 전체 정렬 대신 argpartition 으로 자른다 — 코퍼스 5천 x 질의 340.
    scores = doc_vecs @ q_vecs.T  # (docs, queries)
    by_level: dict[str, dict[str, list[float]]] = {}
    per_query = [] if per_query is None else per_query

    for i, q in enumerate(gold["queries"]):
        relevant = {index[r] for r in q["relevant"] if r in index}
        if not relevant:
            continue  # 정답이 코퍼스에 없는 질의는 분모가 0 이라 건너뛴다
        col = scores[:, i]
        top = np.argpartition(-col, TOP_K)[:TOP_K]
        ranked = [int(d) for d in top[np.argsort(-col[top])]]

        bucket = by_level.setdefault(q.get("level", "all"), {})
        bucket.setdefault("recall", []).append(recall_at_k(ranked, relevant, TOP_K))
        bucket.setdefault("precision", []).append(precision_at_k(ranked, relevant, TOP_K))
        nd = ndcg_at_k(ranked, relevant, TOP_K)
        bucket.setdefault("ndcg", []).append(nd)
        bucket.setdefault("mrr", []).append(reciprocal_rank(ranked, relevant))
        # 질의별 점수. 모델 간 차이가 유의한지는 평균만으로 못 말한다 — 같은 질의에
        # 대한 짝지은 차이가 있어야 부트스트랩 신뢰구간을 낼 수 있다(compare.py).
        per_query.append({"i": i, "level": q.get("level", "all"), "ndcg": round(nd, 6)})

    metrics: dict[str, float] = {}
    pooled: dict[str, list[float]] = {}
    for level, bucket in by_level.items():
        for name, values in bucket.items():
            # mrr 도 @K 다 — 상위 K 만 남기고 자르므로 11위의 정답은 0 으로 센다.
            key = f"{name}_at_{TOP_K}"
            metrics[f"{level}_{key}"] = round(float(np.mean(values)), 4)
            pooled.setdefault(key, []).extend(values)
    for name, values in pooled.items():
        metrics[f"all_{name}"] = round(float(np.mean(values)), 4)
    return metrics


# ── 입출력 ───────────────────────────────────────────────────────────


def load_queries() -> list[str]:
    data = json.loads(QUERY_FIXTURE.read_text(encoding="utf-8"))
    return [q["query"] for q in data["queries"]]


def load_gold(path: Path | None) -> dict | None:
    if path is None:
        return None
    gold = json.loads(path.read_text(encoding="utf-8"))
    if not gold.get("scenes") or not gold.get("queries"):
        raise SystemExit(f"{path}: scenes·queries 가 모두 있어야 한다")
    return gold


def dataset_hash(gold: dict | None) -> str:
    """골드셋 버전 고정. MLflow Evaluation Dataset 의 버전 문서가 얇아 param 으로 박는다."""
    if not gold:
        return "none"
    blob = json.dumps(gold, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()[:12]


def dump_results(results: list[Result], out: Path) -> None:
    """지금까지의 결과를 파일에 덮어쓴다. 중간에 죽어도 앞부분이 남는다."""
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(
            [
                {
                    "model": r.model,
                    "params": r.params,
                    "metrics": r.metrics,
                    "per_query": r.per_query,
                }
                for r in results
            ],
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def print_table(results: list[Result]) -> None:
    keys = ["embedding_dim", "params_m", "query_prefix"]
    metric_keys: list[str] = []
    for r in results:
        for k in r.metrics:
            if k not in metric_keys:
                metric_keys.append(k)

    header = ["model", *keys, *metric_keys]
    rows = [
        [
            r.model,
            *[str(r.params.get(k, "-")) for k in keys],
            *[str(r.metrics.get(k, "-")) for k in metric_keys],
        ]
        for r in results
    ]
    widths = [max(len(h), *(len(row[i]) for row in rows)) for i, h in enumerate(header)]

    def line(cells: list[str]) -> str:
        return " | ".join(c.ljust(w) for c, w in zip(cells, widths, strict=True))

    print()
    print(line(header))
    print("-+-".join("-" * w for w in widths))
    for row in rows:
        print(line(row))


def main() -> None:
    parser = argparse.ArgumentParser(description="한국어 임베딩 모델 비교 (S15P21A501-175)")
    parser.add_argument("--gold", type=Path, help="골드셋 JSON. 없으면 검색 품질은 건너뛴다")
    parser.add_argument("--experiment", default="search-eval-dev")
    parser.add_argument("--tracking-uri", default="http://127.0.0.1:5000")
    parser.add_argument("--no-mlflow", action="store_true", help="콘솔 출력만")
    parser.add_argument("--models", nargs="*", help="기본 후보 3종 대신 지정한 repo 만")
    parser.add_argument(
        "--dims",
        nargs="*",
        type=int,
        default=[],
        help="차원 스윕. 전체 차원보다 작은 값만 쓴다 (예: --dims 256 512 768)",
    )
    parser.add_argument("--out", type=Path, default=RESULTS_DIR / "latest.json")
    parser.add_argument(
        "--dtype",
        choices=["float32", "float16", "bfloat16"],
        help="모델 dtype 을 하나로 맞춘다. 생략하면 모델 카드 기본값",
    )
    parser.add_argument("--self-check", action="store_true", help="지표 검증만 하고 끝낸다")
    args = parser.parse_args()

    # Windows 기본 콘솔이 cp949 라 한글·em dash 출력에서 죽는다. 측정 결과를 잃을 이유가 없다.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    if args.self_check:
        self_check()
        return

    specs = [ModelSpec(m) for m in args.models] if args.models else CANDIDATES
    queries = load_queries()
    gold = load_gold(args.gold)
    ds_hash = dataset_hash(gold)

    if not gold:
        print(
            "골드셋 없음 → recall/ndcg/mrr 건너뜀 (S15P21A501-104 대기). 지연·VRAM·차원만 측정한다."
        )

    mlflow = None
    if not args.no_mlflow:
        import mlflow as _mlflow

        mlflow = _mlflow
        mlflow.set_tracking_uri(args.tracking_uri)
        mlflow.set_experiment(args.experiment)

    results: list[Result] = []
    for spec in specs:
        print(f"\n=== {spec.repo} ===")
        for result in benchmark(spec, queries, gold, args.dims, args.dtype):
            result.params["dataset_hash"] = ds_hash
            result.params["host"] = f"{platform.system()} {platform.machine()}"
            results.append(result)

            dim = result.params["embedding_dim"]
            print(f"  -- dim {dim} --")
            for k, v in result.metrics.items():
                print(f"  {k:<24} {v}")

            if mlflow:
                slug = spec.repo.split("/")[-1].lower()
                with mlflow.start_run(run_name=f"dense-{slug}-d{dim}"):
                    mlflow.log_params(result.params)
                    mlflow.log_metrics(result.metrics)

            # 모델 하나 끝날 때마다 덮어쓴다. 공용 GPU 라 뒤 모델이 OOM 으로 죽는 일이
            # 잦은데(2026-09-11 세 번), 마지막에 한 번만 쓰면 앞서 끝난 모델의 질의별
            # 점수까지 같이 날아간다.
            dump_results(results, args.out)

    print_table(results)
    dump_results(results, args.out)
    print(f"\n→ {args.out}")
    if mlflow:
        print(f"→ MLflow {args.tracking_uri} / experiment={args.experiment}")


if __name__ == "__main__":
    main()
