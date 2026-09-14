"""S15P21A501-100 산출 지표. GPU 서버(jupyter05)에서 돌린다.

**무엇을 재는가.** S15P21A501-175 는 "모델이 좋은가" 를 쟀다. 여기서는 **그 모델을 -100
파이프라인에 태웠을 때 그 성능이 보존되는가** 를 잰다. 둘의 차이는 조립(`compose_text`)·
skip 판정·L2 정규화·검증이다 — -175 는 `model.encode` 를 직접 불렀고 이 스크립트는
`embed_scenes` 를 부른다. 같은 골드셋·같은 모델이므로 ndcg 가 -175 수치에서 벗어나면
그 차이는 전부 파이프라인이 만든 것이다.

**지표를 왜 이 네 갈래로 나눴는가.**

- *품질(라벨 필요)* — 파이프라인 보존 확인용. -175 의 수치와 대조하는 것이 목적이지
  모델을 다시 고르려는 것이 아니다.
- *내재(라벨 불필요)* — 배선 후 운영에서 골드셋 없이 감시할 수 있는 값이다. 근거 논문은
  RankMe(Garrido et al., ICML 2023, arXiv:2210.02885), uniformity(Wang & Isola, ICML 2020,
  arXiv:2005.10242), 이방성 평균 코사인(Ethayarajh, EMNLP 2019).
- *운영* — 처리량·지연·VRAM. -175 는 **질의측** 단건 지연을 쟀다. 문서측은 배치라 다르다.
- *입력 형상* — 토큰 길이 분포. -175 가 `max_seq_length` 를 "장면 텍스트가 짧아 실제로
  걸리지 않는다" 로 판정했는데 그 근거를 실데이터로 확인한다. 잘림이 있으면
  `SceneEmbedding.source_text` 에 남는 전문과 실제 임베딩된 것이 달라진다.

**이 코퍼스의 한계.** 골드셋 `note` 가 적어 둔 그대로다 — 텍스트가 자막 전사뿐이고
캡션이 없다. `SceneText(caption="", dialogue=(text,))` 로 넣으므로 **N-Pick 임베딩 입력의
절반만 잰다.** 캡션을 넣은 장면 단위 골드셋은 S15P21A501-175 의 재평가 조건이다.
"""

import argparse
import json
import os
import platform
import statistics
import time
from pathlib import Path
from typing import Any

import numpy as np

from npick_worker.text_embedding import SceneText, embed_scenes, get_default_config
from npick_worker.text_embedding.sentence_transformers_backend import shared_encoder

EPS = 1e-7


# ── 내재 지표 (라벨 불필요) ─────────────────────────────────────────


def rankme(vectors: np.ndarray) -> float:
    """유효 랭크. Garrido et al., ICML 2023 (arXiv:2210.02885).

    특이값을 확률분포로 정규화한 뒤 Shannon 엔트로피의 exp 를 취한다. 1024 차원을
    실제로 몇 차원이나 쓰고 있는지를 말한다 — 값이 차원보다 크게 낮으면 표현이 좁은
    부분공간에 몰려 있다는 뜻이고, 그만큼 변별에 쓸 축이 적다.

    라벨도 하이퍼파라미터도 필요 없다는 것이 이 지표를 고른 이유다. 배선 후 운영에서
    골드셋 없이 감시할 수 있는 몇 안 되는 값이다.
    """
    singular = np.linalg.svd(vectors, compute_uv=False)
    p = singular / (np.abs(singular).sum() + EPS) + EPS
    return float(np.exp(-(p * np.log(p)).sum()))


def uniformity(vectors: np.ndarray, *, t: float = 2.0, sample: int = 4096) -> float:
    """초구 위 분포의 고름. Wang & Isola, ICML 2020 (arXiv:2005.10242) §4.

    `log E[exp(-t * ||u - v||^2)]`. **낮을수록 고르게 퍼져 있다.** 짝인 alignment 는
    positive pair 가 있어야 해서 여기서는 재지 않는다 — 이 코퍼스에는 캡션이 없어
    "같은 장면의 캡션과 대사" 라는 자연 positive pair 를 만들 수 없다.

    L2 정규화된 벡터를 전제한다. 전수 계산은 O(n^2) 이라 표본을 쓴다.
    """
    rng = np.random.default_rng(175)
    picked = vectors[rng.choice(len(vectors), min(sample, len(vectors)), replace=False)]
    sq = np.maximum(0.0, 2.0 - 2.0 * (picked @ picked.T))
    upper = sq[np.triu_indices(len(picked), k=1)]
    return float(np.log(np.exp(-t * upper).mean() + EPS))


def mean_pairwise_cosine(vectors: np.ndarray, *, sample: int = 4096) -> float:
    """무작위 쌍의 평균 코사인 — 이방성(Ethayarajh, EMNLP 2019).

    1 에 가까우면 모든 벡터가 좁은 원뿔에 몰려 서로 구별되지 않는다. RankMe 와 같은
    현상의 읽기 쉬운 면이고, 둘을 함께 보면 "차원은 쓰는데 뭉쳐 있다" 같은 경우를 가른다.
    """
    rng = np.random.default_rng(175)
    picked = vectors[rng.choice(len(vectors), min(sample, len(vectors)), replace=False)]
    sims = picked @ picked.T
    return float(sims[np.triu_indices(len(picked), k=1)].mean())


# ── 품질 지표 (골드셋 라벨) ─────────────────────────────────────────


def ndcg_at_k(ranked: list[str], relevant: set[str], k: int) -> float:
    gains = [1.0 / np.log2(i + 2) for i, doc in enumerate(ranked[:k]) if doc in relevant]
    ideal = [1.0 / np.log2(i + 2) for i in range(min(len(relevant), k))]
    return float(sum(gains) / sum(ideal)) if ideal else 0.0


def recall_at_k(ranked: list[str], relevant: set[str], k: int) -> float:
    if not relevant:
        return 0.0
    return len(set(ranked[:k]) & relevant) / len(relevant)


def reciprocal_rank(ranked: list[str], relevant: set[str]) -> float:
    for i, doc in enumerate(ranked):
        if doc in relevant:
            return 1.0 / (i + 1)
    return 0.0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold", required=True)
    parser.add_argument("--experiment", default="stage-text-embedding-v1")
    parser.add_argument("--run-name", default=None)
    parser.add_argument("--limit", type=int, default=0, help="문서 수 상한(0=전체)")
    parser.add_argument("--latency-samples", type=int, default=200)
    parser.add_argument("--no-mlflow", action="store_true")
    args = parser.parse_args()

    import torch

    gold = json.loads(Path(args.gold).read_text(encoding="utf-8"))
    docs = gold["scenes"][: args.limit] if args.limit else gold["scenes"]
    doc_ids = [d["id"] for d in docs]
    keep = set(doc_ids)

    config = get_default_config()
    encoder = shared_encoder()

    # ── 산출: 우리 모듈을 그대로 탄다 ──────────────────────────────
    # scene_index 는 int 여야 하므로 골드셋 id 와의 매핑을 따로 들고 있는다.
    scenes = [
        SceneText(scene_index=i, caption="", dialogue=(d["text"],)) for i, d in enumerate(docs)
    ]

    torch.cuda.reset_peak_memory_stats()
    started = time.perf_counter()
    result = embed_scenes(scenes, encoder=encoder, config=config)
    elapsed = time.perf_counter() - started
    vram_peak_mb = torch.cuda.max_memory_allocated() / (1024 * 1024)

    vectors = np.asarray([s.vector for s in result.scenes], dtype=np.float32)
    embedded_ids = [doc_ids[s.scene_index] for s in result.scenes]

    # ── 품질: 질의는 접두가 다르다(`query: `). 문서측 모듈을 쓰지 않는다 ──
    prefix = "query: "
    queries = [q for q in gold["queries"] if set(q["relevant"]) & keep]
    query_vectors = np.asarray(
        encoder.encode([prefix + q["query"] for q in queries]), dtype=np.float32
    )
    query_vectors /= np.linalg.norm(query_vectors, axis=1, keepdims=True) + EPS

    scores = query_vectors @ vectors.T
    top = np.argsort(-scores, axis=1)[:, :100]

    # **레벨을 섞어 평균 내지 않는다.** 골드셋 질의는 summary·category·event 세 레벨이고
    # S15P21A501-175 의 비교표는 **summary 만**의 값이다. 섞으면 그 티켓이 "구조적으로
    # 무효" 로 판정한 category(22건 중 20건이 화면 설명을 자막에 매칭하는 구조)와
    # dense 가 원래 못 잡는 event(방송일로만 갈리는 사건, 전 모델 ndcg 0.115~0.125)가
    # 함께 들어가 값이 내려간다 — 모델도 파이프라인도 아닌 집계 방식의 차이다.
    # hard·broad 는 전부 summary 라 이 구분이 드러나지 않았다.
    by_level: dict[str, dict[str, list[float]]] = {}
    for row, query in zip(top, queries, strict=True):
        ranked = [embedded_ids[i] for i in row]
        relevant = set(query["relevant"]) & keep
        bucket = by_level.setdefault(query.get("level", "all"), {})
        bucket.setdefault("ndcg", []).append(ndcg_at_k(ranked, relevant, 10))
        bucket.setdefault("recall", []).append(recall_at_k(ranked, relevant, 10))
        bucket.setdefault("mrr", []).append(reciprocal_rank(ranked, relevant))

    # 대표값은 summary 다(-175 비교표와 같은 자리). 레벨이 하나뿐인 골드셋에서는
    # 그것이 곧 전체다.
    primary = "summary" if "summary" in by_level else next(iter(by_level))
    ndcg10 = by_level[primary]["ndcg"]
    recall10 = by_level[primary]["recall"]
    rr = by_level[primary]["mrr"]

    # ── 운영: 단건 지연은 배치와 다른 값이다 ──────────────────────
    sample_texts = [d["text"] for d in docs[: args.latency_samples]]
    encoder.encode(sample_texts[:8])  # 워밍업
    single = []
    for text in sample_texts:
        t0 = time.perf_counter()
        encoder.encode([text])
        single.append((time.perf_counter() - t0) * 1000)

    # ── 입력 형상: 잘림이 실제로 일어나는가 ───────────────────────
    from transformers import AutoTokenizer

    model_id = os.environ["NPICK_AI_EMBEDDING_MODEL"]
    tokenizer = AutoTokenizer.from_pretrained(model_id)
    max_len = getattr(tokenizer, "model_max_length", 0)
    lengths = [len(tokenizer.encode(s.source_text, add_special_tokens=True)) for s in result.scenes]
    truncated = sum(1 for length in lengths if length > max_len)

    # **환경을 반드시 남긴다.** 지연·처리량·VRAM 은 하드웨어와 torch 빌드가 바뀌면
    # 그대로 달라지는데, 이 값들 없이는 나중에 두 run 을 비교할 수 없다. GPU 서버
    # 드라이버가 CUDA 12.8 이라 프로젝트 lock 의 cu130 휠로는 GPU 를 못 잡고 cu128 로
    # 내려야 한다(eval/embedding/README.md 가 적어 둔 함정). 그래서 **측정 환경과 배포
    # 환경의 torch 빌드는 원래 다를 수 있다** — 숨기지 말고 기록해 해석에 쓴다.
    params: dict[str, Any] = {
        "torch_version": torch.__version__,
        "torch_cuda": torch.version.cuda,
        "host_gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu",
        "host_platform": f"{platform.system()} {platform.machine()}",
        "python_version": platform.python_version(),
        "model_version": result.model_version,
        "engine": result.engine,
        "engine_version": result.engine_version,
        "config_version": result.config_version,
        "dimension": result.dimension,
        "normalize": config.normalize,
        "document_prefix": repr(config.document_prefix),
        "gold": Path(args.gold).name,
        "corpus_size": len(docs),
        "query_count": len(queries),
        "primary_level": primary,
        "gold_levels": ",".join(f"{k}:{len(v['ndcg'])}" for k, v in sorted(by_level.items())),
        "device": "cuda",
        "max_seq_length": max_len,
    }
    metrics: dict[str, float] = {
        # 품질 — -175 수치와 대조하는 것이 목적이다.
        # MLflow 는 metric 이름에 `@` 를 허용하지 않는다(영숫자·_·-·.·공백·:·/ 만).
        "ndcg_at_10": float(np.mean(ndcg10)),
        "recall_at_10": float(np.mean(recall10)),
        "mrr": float(np.mean(rr)),
        # 레벨별 — -175 가 category 를 무효로, event 를 FR-SRH-002 의 증거로 판정했다.
        # 대표값과 따로 남겨야 나중에 어느 레벨이 움직였는지 말할 수 있다.
        **{f"ndcg_at_10_{level}": float(np.mean(vals["ndcg"])) for level, vals in by_level.items()},
        **{f"query_count_{level}": float(len(vals["ndcg"])) for level, vals in by_level.items()},
        # 내재 — 라벨 없이 운영에서 감시 가능
        "rankme": rankme(vectors),
        "rankme_ratio": rankme(vectors) / result.dimension,
        "uniformity": uniformity(vectors),
        "mean_pairwise_cosine": mean_pairwise_cosine(vectors),
        # 운영
        "throughput_scenes_per_sec": len(result.scenes) / elapsed,
        "batch_total_sec": elapsed,
        "latency_single_p50_ms": statistics.median(single),
        "latency_single_p95_ms": float(np.percentile(single, 95)),
        "vram_peak_mb": vram_peak_mb,
        "skip_ratio": result.skipped_count / max(1, len(scenes)),
        "skipped": float(result.skipped_count),
        "embedded": float(result.embedded_count),
        # 입력 형상
        "token_len_p50": float(np.percentile(lengths, 50)),
        "token_len_p95": float(np.percentile(lengths, 95)),
        "token_len_max": float(max(lengths)),
        "truncated_scenes": float(truncated),
    }

    for key, value in params.items():
        print(f"  {key} = {value}")
    for key, value in metrics.items():
        print(f"  {key} = {value:.6g}")

    if not args.no_mlflow:
        import mlflow

        mlflow.set_experiment(args.experiment)
        name = args.run_name or f"emit-{Path(args.gold).stem}"
        with mlflow.start_run(run_name=name):
            mlflow.log_params(params)
            mlflow.log_metrics(metrics)
        print(f"\nMLflow run 기록: experiment={args.experiment} run={name}")


if __name__ == "__main__":
    main()
