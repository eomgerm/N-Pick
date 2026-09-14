"""b-roll 프로토타입 큐레이션 → 임베딩 평가 골드셋 (S15P21A501-175 / -104).

입력은 b-roll 저장소의 두 파일이다.

- `71699_index.jsonl` — AI-Hub 71699(한국어 텍스트-비디오-사운드) KBS 뉴스 클립 인덱스.
  `id` `date` `sum`(사람 요약) `txt`(자막 전사).
- `broll-holiday-curation.json` — b-roll 프로토타입에서 **사람이 손으로 큐레이션한**
  명절 귀성길 평가셋(원본 이름 `curation_v4.json`). 명절 사건 20건과 그 관련 클립,
  장소 21종, 혼동 이웃(distractor) 4,315건. 클립 ID 와 사건 정의만 담고 본문은 없어서
  이 저장소에 커밋돼 있다. **재생성 불가능한 유일한 자산이다** — 무작위 표본은 언제든
  다시 뽑을 수 있지만 이 라벨은 사람이 만든 것이다.

라벨을 새로 만들지 않는다. **이미 사람이 붙인 것만 질의 형태로 옮긴다.**

## 질의 3레벨

b-roll `CONTEXT.md` 의 검색 레벨(사건 / 사건 유형 / no-event)을 그대로 따른다.
레벨마다 재는 것이 다르므로 지표도 레벨별로 갈라서 본다.

`event` — 질의 "2022년 추석 귀성길", 정답은 그 사건의 클립.
    **dense 의 천장을 재는 자리.** 2022 추석과 2023 설날은 화면도 자막도 같고
    방송일로만 갈리는데 dense 는 날짜를 못 본다. 낮게 나오는 것이 정상이고
    FR-SRH-002(임베딩은 exact term 매칭 대체 불가)의 근거가 된다.

`category` — 질의 "명절 귀성길 고속도로 정체", 정답은 축·장소로 묶인 클립.
    변별용으로 만들었지만 **실측 결과 모델 선정에 쓸 수 없다.** 22건 중 20건이 장소
    코드북 라벨("행사/사무공간 나오는 자료화면")인데, 그건 **화면**을 설명하는 라벨이고
    우리가 매칭하는 것은 **자막**이다. 정보가 텍스트에 없어 전 모델이 0.04~0.11 로
    바닥이다. 미스매치가 없는 축 질의 2건에서는 상위 두 모델이 둘 다 1.0 이다.
    캡션을 임베딩 입력에 넣은 뒤에야 이 축이 의미를 갖는다.

`summary` — 질의는 사람이 쓴 클립 요약, 정답은 그 클립 1건.
    대량 표본(1:1). 자막에서 파생된 요약이라 어휘 겹침이 크지만 모든 모델에
    똑같이 유리하므로 비교에는 쓸 수 있다.

## 우리 도메인과 어긋나는 지점 — 숫자를 읽을 때 감안할 것

1. **단위가 클립이다.** N-Pick 의 단위는 장면이고 클립 하나에 장면이 평균 6.7개다(FRD).
   모델 간 상대 순위는 유지되지만 절대 recall 은 장면 단위로 옮겨오지 않는다.
2. **캡션이 없다.** 여기 텍스트는 자막 전사뿐인데 N-Pick 의 임베딩 입력은 캡션 + 대사다.
   즉 입력의 절반만 재고 있다.
3. **요약 질의는 편집기자의 말투가 아니다.** 실제 질의는 "2022년 추석 고속도로 정체
   자료화면 좀 찾아줘" 같은 짧은 구어다. `summary` 레벨 점수는 낙관적으로 읽어야 한다.
4. **summary 의 1:1 정답에는 근접 중복 문제가 있다.** 뉴스는 재방·후속 보도로 자막이
   거의 같은 클립이 있는데, 그런 클립이 1위로 오면 정답인데 오답으로 채점된다. 세 모델에
   똑같이 걸리므로 상대 비교는 유지되지만 절대 수치는 그만큼 과소평가다.

    .venv-eval/bin/python eval/embedding/build_gold.py --source <b-roll>/data
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path
from typing import Any

BENCH_DIR = Path(__file__).resolve().parent
# 커밋된 큐레이션 라벨. 본문이 없어 저장소에 둘 수 있다(자막 전사는 골드셋에만 들어간다).
DEFAULT_CURATION = BENCH_DIR / "broll-holiday-curation.json"

# 장소 코드북 이름은 분류 라벨이라 그대로는 질의가 아니다. 편집기자가 칠 법한 문장으로 감싼다.
LOCATION_TEMPLATE = "{place} 나오는 자료화면"

# curation_v4 의 policy.axes 를 질의 문장으로 옮긴 것. 축 자체는 큐레이션이 정한 것이고
# 여기서 하는 일은 키워드 나열을 한 문장으로 만드는 것뿐이다.
AXIS_QUERIES = {
    "traffic_clips": "명절 귀성길 고속도로 정체와 서울역 터미널 인파",
    "scene_clips": "명절 차례상 준비와 전통시장 풍경",
}


def load_index(path: Path, wanted: set[str]) -> dict[str, dict[str, Any]]:
    """180,111줄을 다 들고 있을 이유가 없다 — 큐레이션에 등장하는 id 만 남긴다."""
    index: dict[str, dict[str, Any]] = {}
    with path.open(encoding="utf-8") as f:
        for line in f:
            record = json.loads(line)
            if record["id"] in wanted:
                index[record["id"]] = record
    return index


def build(source: Path, curation_path: Path, summary_queries: int, seed: int) -> dict[str, Any]:
    curation = json.loads(curation_path.read_text(encoding="utf-8"))

    corpus_ids: set[str] = set()
    for event in curation["events"]:
        corpus_ids |= set(event["clips"])
    for clips in curation["locations"].values():
        corpus_ids |= set(clips)
    for clips in curation["distractors"].values():
        corpus_ids |= set(clips)

    index = load_index(source / "71699_index.jsonl", corpus_ids)
    missing = corpus_ids - index.keys()
    if missing:
        raise SystemExit(f"인덱스에 없는 클립 {len(missing)}건: {sorted(missing)[:5]}")

    # 자막이 비어 있으면 임베딩할 것이 없다. 코퍼스에서 빼고 정답에서도 빼야 분모가 맞는다.
    usable = {cid for cid in corpus_ids if index[cid].get("txt", "").strip()}
    dropped = corpus_ids - usable

    scenes = [{"id": cid, "text": index[cid]["txt"]} for cid in sorted(usable)]
    queries: list[dict[str, Any]] = []

    # ── event: 날짜로만 갈리는 사건. dense 가 못 푸는 자리 ──
    for event in curation["events"]:
        if not event.get("event_ready"):
            continue
        relevant = sorted(set(event["clips"]) & usable)
        if relevant:
            queries.append(
                {
                    "query": f"{event['name']} 귀성길 자료화면",
                    "relevant": relevant,
                    "level": "event",
                }
            )

    # ── category: 날짜와 무관한 의미 검색 ──
    # 축 질의는 event 레벨과 달리 `event_ready` 를 거르지 않는다. 의도한 비대칭이다 —
    # `event_ready` 는 "그 사건 하나로 질의가 성립하는가"인데, 축 질의는 전 사건의
    # 클립을 합쳐 하나의 주제로 묻기 때문에 개별 사건의 성립 여부와 무관하다.
    for key, text in AXIS_QUERIES.items():
        relevant = sorted({c for e in curation["events"] for c in e.get(key, [])} & usable)
        if relevant:
            queries.append({"query": text, "relevant": relevant, "level": "category"})

    for place, clips in curation["locations"].items():
        if place == "알 수 없음":  # 코드 21. 장소를 모른다는 뜻이라 질의가 성립하지 않는다
            continue
        relevant = sorted(set(clips) & usable)
        if relevant:
            queries.append(
                {
                    "query": LOCATION_TEMPLATE.format(place=place),
                    "relevant": relevant,
                    "level": "category",
                }
            )

    # ── summary: 1:1 대량 표본 ──
    have_summary = sorted(cid for cid in usable if index[cid].get("sum", "").strip())
    sampled = random.Random(seed).sample(have_summary, min(summary_queries, len(have_summary)))
    for cid in sampled:
        queries.append(
            {
                "query": index[cid]["sum"],
                "relevant": [cid],
                "level": "summary",
            }
        )

    return {
        "source": "broll-holiday-curation + AI-Hub 71699 index",
        "note": "사람이 붙인 라벨만 옮긴 것이다. 단위는 클립이고 텍스트는 자막 전사뿐이다 "
        "— 캡션이 없으므로 N-Pick 임베딩 입력의 절반만 잰다.",
        "seed": seed,
        "dropped_empty_txt": len(dropped),
        "scenes": scenes,
        "queries": queries,
    }


def build_hard(
    source: Path, category: str, corpus_size: int, n_queries: int, seed: int
) -> dict[str, Any]:
    """**한 카테고리 안에서만** 코퍼스를 뽑아 오답 후보를 주제적으로 동질하게 만든다.

    2026-09-11 실측: 코퍼스를 5,537 → 50,000 으로 9배 키웠더니 점수가 오히려 올랐고
    (0.9578 → 0.9751) 모델 간 격차는 0.0115 → 0.0036 으로 좁아졌다. 무작위 표본은
    정치·스포츠·날씨가 섞여 있어 오답이 죄다 딴 얘기라 정답이 쉽게 튄다.

    **검색을 어렵게 만드는 것은 코퍼스 크기가 아니라 주제 동질성이다.** 같은 카테고리
    안에서만 뽑으면 모든 오답이 그럴듯해져서 모델이 갈린다.
    """
    rng = random.Random(seed)
    records = [json.loads(line) for line in (source / "71699_index.jsonl").open(encoding="utf-8")]
    pool = [
        r
        for r in records
        if r.get("cat") == category and r.get("txt", "").strip() and r.get("sum", "").strip()
    ]
    if not pool:
        cats = sorted({r.get("cat", "?") for r in records})
        raise SystemExit(f"카테고리 '{category}' 가 없다. 가능한 값: {cats}")
    rng.shuffle(pool)

    corpus = pool[:corpus_size]
    return {
        "source": f"AI-Hub 71699 index · cat={category} 한정 ({len(corpus)}건, seed={seed})",
        "note": "오답 후보가 전부 같은 주제라 의미 구분이 어렵다 — 모델 변별용 세트다.",
        "seed": seed,
        "category": category,
        "dropped_empty_txt": 0,
        "scenes": [{"id": r["id"], "text": r["txt"]} for r in corpus],
        "queries": [
            {"query": r["sum"], "relevant": [r["id"]], "level": "summary"}
            for r in rng.sample(corpus, min(n_queries, len(corpus)))
        ],
    }


def build_broad(source: Path, corpus_size: int, n_queries: int, seed: int) -> dict[str, Any]:
    """전체 인덱스에서 표본을 뽑아 **더 크고 더 어려운** 코퍼스를 만든다.

    큐레이션 골드셋은 코퍼스가 5,537건이라 1:1 질의가 너무 쉽다 — summary ndcg 가
    0.94~0.96 에 몰려 모델이 갈리지 않는다(2026-09-11 실측). 코퍼스를 10배로 키우면
    오답 후보가 늘어 점수가 내려가고 모델 간 간격이 벌어진다.

    라벨은 여전히 사람이 만든 것이다 — 질의는 사람이 쓴 클립 요약이고 정답은 그 클립이다.
    다만 이 세트에는 `category`·`event` 레벨이 없다. 그건 큐레이션에만 있다.
    """
    rng = random.Random(seed)
    records = [json.loads(line) for line in (source / "71699_index.jsonl").open(encoding="utf-8")]
    usable = [r for r in records if r.get("txt", "").strip() and r.get("sum", "").strip()]
    rng.shuffle(usable)

    corpus = usable[:corpus_size]
    scenes = [{"id": r["id"], "text": r["txt"]} for r in corpus]
    queries = [
        {"query": r["sum"], "relevant": [r["id"]], "level": "summary"}
        for r in rng.sample(corpus, min(n_queries, len(corpus)))
    ]
    return {
        "source": f"AI-Hub 71699 index (무작위 {len(corpus)}건, seed={seed})",
        "note": "질의는 사람이 쓴 요약, 정답은 그 클립 1건. 단위는 클립이고 캡션은 없다.",
        "seed": seed,
        "dropped_empty_txt": len(records) - len(usable),
        "scenes": scenes,
        "queries": queries,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="b-roll 큐레이션 → 골드셋")
    parser.add_argument("--source", type=Path, required=True, help="b-roll data/ 디렉터리")
    parser.add_argument("--out", type=Path, default=BENCH_DIR / "gold.json")
    parser.add_argument(
        "--curation",
        type=Path,
        default=DEFAULT_CURATION,
        help="curation 모드 라벨 파일. 기본은 저장소에 커밋된 사본",
    )
    parser.add_argument(
        "--mode",
        choices=["curation", "broad", "hard"],
        default="curation",
        help="curation=큐레이션 3레벨, broad=전체 무작위, hard=한 카테고리 한정",
    )
    parser.add_argument("--category", default="사건사고뉴스", help="hard 모드 카테고리")
    parser.add_argument(
        "--corpus-size", type=int, default=50_000, help="broad·hard 모드 코퍼스 크기"
    )
    parser.add_argument("--summary-queries", type=int, default=300)
    parser.add_argument("--seed", type=int, default=175)
    args = parser.parse_args()

    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    if args.mode == "hard":
        gold = build_hard(
            args.source.expanduser(),
            args.category,
            args.corpus_size,
            args.summary_queries,
            args.seed,
        )
    elif args.mode == "broad":
        gold = build_broad(
            args.source.expanduser(), args.corpus_size, args.summary_queries, args.seed
        )
    else:
        gold = build(args.source.expanduser(), args.curation, args.summary_queries, args.seed)
    args.out.write_text(json.dumps(gold, ensure_ascii=False, indent=1), encoding="utf-8")

    levels: dict[str, list[int]] = {}
    for q in gold["queries"]:
        levels.setdefault(q["level"], []).append(len(q["relevant"]))

    print(f"코퍼스 {len(gold['scenes'])}건 (자막 없음 {gold['dropped_empty_txt']}건 제외)")
    for level, sizes in levels.items():
        sizes.sort()
        print(
            f"  {level:<9} 질의 {len(sizes):>3}건 · 정답 수 "
            f"min {sizes[0]} / 중앙 {sizes[len(sizes) // 2]} / max {sizes[-1]}"
        )
    print(f"→ {args.out}")


if __name__ == "__main__":
    main()
