"""Score any extraction method against the gold sheet the stage was reviewed with.

`entity_gold_review.py` builds and scores the gold sheet for the shipped stage. This
tool answers the other question: **on the same input and the same gold, how does a
different method do?** It takes a result file from `entity_sample_compare.py` and the
filled gold sheet, and produces the same precision/recall numbers so the two can be put
in one table.

    cd ai && GOLD=samples/out/entity-gold-99
    RESULT=samples/out/entity-compare-20260916/result-llm-Qwen--Qwen3-1.7B.json
    python tools/entity_gold_compare.py build \
        --result $RESULT --gold $GOLD/sheet.json --out $GOLD/compare-llm.json
    python tools/entity_gold_compare.py score \
        --sheet $GOLD/compare-llm.json --gold $GOLD/sheet.json \
        --out $GOLD/compare-llm-metrics.json

Two things are deliberately not computed here. `screenTruth` is a property of the OCR
text, not of the method reading it, so the end-to-end precision figure is taken from the
gold sheet and applies to every method equally. And a method that answers only some of
the scenes gets **two** recall figures: over all reviewed scenes, and over the scenes it
actually answered. Reporting only the second one hides a method that gives up.

**The sheet carries AI-Hub/KBS subtitle and screen text**, so it is written under
`ai/samples/` where `ai/.gitignore` keeps it. Only the metrics file is meant to leave
that tree.
"""

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from entity_gold_review import ENTITY_TYPES, VERDICTS, ratio, read, write

from npick_worker.entity_extraction.extractor import match_key

Key = tuple[str, int, str, str]


GoldSets = tuple[set[Key], set[tuple[str, int, str]], set[tuple[str, int]]]


def gold_sets(gold: dict[str, Any]) -> GoldSets:
    """정답 집합은 단계 검수 시트 하나에서만 나온다. 방법마다 다른 정답을 쓰면 비교가 아니다.

    반환: (유형까지 맞춘 정답, 유형을 뺀 정답값, 재현율을 셀 수 있는 장면)
    """
    keyed: set[Key] = set()
    valued: set[tuple[str, int, str]] = set()
    scenes: set[tuple[str, int]] = set()
    for scene in gold["scenes"]:
        if not scene["missedReviewed"]:
            continue
        where = (scene["clip"], scene["sceneIndex"])
        scenes.add(where)
        for candidate in scene["candidates"]:
            key = match_key(candidate["value"])
            if candidate["verdict"] == "correct":
                keyed.add((*where, candidate["type"], key))
            elif candidate["verdict"] == "type_wrong":
                keyed.add((*where, candidate["goldType"], key))
            else:
                continue
            valued.add((*where, key))
        for miss in scene["missed"]:
            if miss.get("upstream"):
                # 화면에만 있고 OCR 이 넘겨주지 않은 개체. 어느 방법도 볼 수 없었다.
                continue
            keyed.add((*where, miss["type"], match_key(miss["value"])))
            valued.add((*where, match_key(miss["value"])))
    return keyed, valued, scenes


def texts_by_scene(gold: dict[str, Any]) -> dict[tuple[str, int], list[dict[str, Any]]]:
    return {(s["clip"], s["sceneIndex"]): s["texts"] for s in gold["scenes"]}


def build(result: dict[str, Any], gold: dict[str, Any]) -> dict[str, Any]:
    """방법 결과를 판정 가능한 시트로 바꾼다. 정답과 정확히 일치하는 것만 미리 채운다.

    정답에 없는 후보를 자동으로 오답 처리하지 않는 것이 중요하다. 정답표는 KPF 출력에
    사람이 누락을 더해 만든 것이라, 다른 방법만 찾아낸 진짜 개체가 있을 수 있다. 그건
    사람이 보고 correct 로 적어야 하고, 그때 정답표에도 누락으로 더해야 공평해진다.
    """
    keyed, valued, scenes = gold_sets(gold)
    texts = texts_by_scene(gold)
    by_scene: dict[tuple[str, int], list[dict[str, Any]]] = defaultdict(list)
    outside = 0
    for candidate in result["candidates"]:
        where = (candidate["clip"], candidate["sceneIndex"])
        if where not in scenes:
            outside += 1
            continue
        by_scene[where].append(candidate)

    out_scenes = []
    prefilled = 0
    for where in sorted(by_scene):
        rows = []
        for index, candidate in enumerate(by_scene[where]):
            key = match_key(candidate["value"])
            verdict = None
            gold_type = None
            note = ""
            if (*where, candidate["type"], key) in keyed:
                verdict, note = "correct", "정답과 유형·값이 일치"
                prefilled += 1
            elif (*where, key) in valued:
                verdict, note = "type_wrong", "정답에 같은 값이 다른 유형으로 있다"
                gold_type = next(t for w in [where] for t in ENTITY_TYPES if (*w, t, key) in keyed)
                prefilled += 1
            rows.append(
                {
                    "id": f"{where[0]}#{where[1]}#{index}",
                    "type": candidate["type"],
                    "value": candidate["value"],
                    "textId": candidate.get("textId"),
                    "kind": candidate.get("kind"),
                    "confidence": candidate.get("confidence"),
                    "verdict": verdict,
                    "goldType": gold_type,
                    "note": note,
                }
            )
        out_scenes.append(
            {
                "clip": where[0],
                "sceneIndex": where[1],
                "texts": texts[where],
                "candidates": rows,
            }
        )
    return {
        "schema": "npick.entity-extraction.method-compare/v1",
        "method": result["method"],
        "engine": result.get("engine"),
        "device": result.get("device"),
        "seconds": result.get("seconds"),
        "goldSheet": gold.get("run"),
        "goldLabeledBy": gold.get("labeledBy"),
        "scenesFailed": result.get("scenesFailed", []),
        "candidatesOutsideGold": outside,
        "prefilled": prefilled,
        "labeledBy": None,
        "instructions": [
            f"verdict 는 {sorted(VERDICTS)} 중 하나. 뜻은 entity_gold_review.py 의 "
            "VERDICTS 와 같다.",
            "미리 채워진 correct/type_wrong 은 정답표와 기계적으로 맞춰본 것이다. 되짚어도 된다.",
            "정답표에 없는 진짜 개체를 이 방법이 찾았으면 correct 로 적고, 같은 항목을 "
            "gold 시트의 그 장면 missed 에도 더해야 한다. 안 그러면 이 방법에만 불리하다.",
        ],
        "scenes": out_scenes,
    }


def score(sheet: dict[str, Any], gold: dict[str, Any]) -> dict[str, Any]:
    """precision 은 이 방법의 후보를, recall 은 공통 정답을 분모로 센다."""
    keyed, _, scenes = gold_sets(gold)
    verdicts: Counter[str] = Counter()
    by_type: dict[str, Counter[str]] = defaultdict(Counter)
    by_kind: dict[str, Counter[str]] = defaultdict(Counter)
    unreviewed: list[str] = []
    found: set[Key] = set()
    answered: set[tuple[str, int]] = set()
    for scene in sheet["scenes"]:
        where = (scene["clip"], scene["sceneIndex"])
        answered.add(where)
        for candidate in scene["candidates"]:
            verdict = candidate["verdict"]
            if verdict is None:
                unreviewed.append(candidate["id"])
                continue
            if verdict not in VERDICTS:
                raise ValueError(f"unknown verdict {verdict!r} on {candidate['id']}")
            verdicts[verdict] += 1
            by_type[candidate["type"]][verdict] += 1
            by_kind[candidate.get("kind") or "unknown"][verdict] += 1
            if verdict == "correct":
                found.add((*where, candidate["type"], match_key(candidate["value"])))
    reviewed = sum(verdicts.values())
    readable = reviewed - verdicts["unreadable_source"]
    gold_answered = {g for g in keyed if (g[0], g[1]) in answered}
    return {
        "schema": "npick.entity-extraction.method-compare-metrics/v1",
        "method": sheet["method"],
        "engine": sheet.get("engine"),
        "device": sheet.get("device"),
        "seconds": sheet.get("seconds"),
        "goldLabeledBy": sheet.get("goldLabeledBy"),
        "labeledBy": sheet.get("labeledBy"),
        "coverage": {
            "goldScenes": len(scenes),
            "scenesAnswered": len(answered),
            "scenesFailed": len(sheet.get("scenesFailed", [])),
            "candidates": reviewed + len(unreviewed),
            "reviewed": reviewed,
            "unreviewed": len(unreviewed),
            "candidatesOutsideGold": sheet.get("candidatesOutsideGold", 0),
        },
        "verdicts": dict(verdicts),
        "precision": {
            "strict": ratio(verdicts["correct"], reviewed),
            "attributable": ratio(verdicts["correct"], readable),
            "attributableDenominator": readable,
        },
        "recall": {
            # 침묵한 장면까지 포함한 값이 실제로 쓸 수 있는 재현율이다.
            "stage": ratio(len(found), len(keyed)),
            "stageGold": len(keyed),
            # 답을 낸 장면만 놓고 본 값. 침묵을 빼면 얼마나 잘하는지를 보여준다.
            "answeredScenes": ratio(len(found), len(gold_answered)),
            "answeredScenesGold": len(gold_answered),
            "found": len(found),
        },
        "byType": {k: dict(c) for k, c in sorted(by_type.items())},
        "bySourceKind": {k: dict(c) for k, c in sorted(by_kind.items())},
        "unreviewed": unreviewed[:50],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    builder = sub.add_parser("build", help="method result + gold sheet -> review sheet")
    builder.add_argument("--result", type=Path, required=True)
    builder.add_argument("--gold", type=Path, required=True)
    builder.add_argument("--out", type=Path, required=True)
    scorer = sub.add_parser("score", help="filled review sheet + gold sheet -> precision/recall")
    scorer.add_argument("--sheet", type=Path, required=True)
    scorer.add_argument("--gold", type=Path, required=True)
    scorer.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "build":
        sheet = build(read(args.result), read(args.gold))
        write(args.out, sheet)
        keys = ("method", "prefilled", "candidatesOutsideGold")
        print(json.dumps({k: sheet[k] for k in keys}, ensure_ascii=False))
        return
    metrics = score(read(args.sheet), read(args.gold))
    write(args.out, metrics)
    print(json.dumps(metrics["coverage"], ensure_ascii=False))


if __name__ == "__main__":
    main()
