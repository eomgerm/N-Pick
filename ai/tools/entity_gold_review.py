"""Human gold review for stage 8 entity candidates: build the sheet, then score it.

`build` turns a saved report run into one reviewable sheet — every scene's source text
next to the candidates that came out of it, plus the contributing NER label so a wrong
type can be traced to the label that produced it. `score` reads the same file back after
a person has filled it in and computes precision/recall.

    cd ai && GOLD=samples/out/entity-gold-99
    python tools/entity_gold_review.py build \
        --run samples/out/entity-extraction-99-20260916 --out $GOLD/sheet.json
    python tools/entity_gold_review.py score \
        --sheet $GOLD/sheet.json --out $GOLD/metrics.json

**The sheet carries AI-Hub/KBS subtitle and screen text**, so it is written under
`ai/samples/` where `ai/.gitignore` keeps it. Only the metrics file and a label file
without source sentences are meant to leave that tree.
"""

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from npick_worker.entity_extraction.config import load_config
from npick_worker.entity_extraction.extractor import match_key

# 후보 하나에 축을 둘 매긴다. verdict 는 **이 단계가 받은 텍스트를 기준으로** 옳았는지고,
# screenTruth 는 그 텍스트가 화면/음성 실제와 같은지다. 섞으면 OCR 오독이 추출 오류로
# 계산되고, 그러면 어느 단계를 고쳐야 하는지가 숫자에서 사라진다.
VERDICTS = {
    "correct": "받은 텍스트 기준 유형과 값이 모두 맞다. 그 텍스트에 개체가 통째로 있다",
    "type_wrong": "실제 개체이긴 한데 유형이 틀렸다. 맞는 유형을 goldType 에 적는다",
    "fragment": "개체의 일부만 잘라냈다. 온전한 형태는 missed 에 적는다",
    "overspan": "개체 경계를 넘어 옆말까지 삼켰다(`LG감독`). 온전한 형태는 missed 에 적는다",
    "not_entity": "그 텍스트는 말이 되지만 이 문자열은 6종 어느 개체도 아니다",
    "unreadable_source": "받은 텍스트가 애초에 말이 아니다(OCR 깨짐). 옳은 답이 존재하지 않는다",
}

SCREEN_TRUTH = {
    "faithful": "키프레임을 보니 화면 글자가 이 텍스트 그대로다",
    "misread": "화면에는 다른 글자가 있다. OCR 오독이라 태그 값이 실제와 다르다",
    "unknown": "아직 키프레임을 확인하지 않았다. 실물 대조 지표의 분모에서 빠진다",
}

ENTITY_TYPES = ("person", "organization", "location", "facility", "keyword", "event")

FRAME_ROOT = Path("ai/samples/out/ocr-sports-10-20260915")


def read(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def frame_path(clip: str, storage_key: str) -> str:
    """사람이 '화면에 정말 그 글자가 있었나'를 볼 수 있어야 unreadable_source 판정이 선다."""
    return str(FRAME_ROOT / clip / "frames" / storage_key).replace("\\", "/")


def contributing_spans(
    texts: list[dict[str, Any]],
    predictions: dict[tuple[int, str], list[dict[str, Any]]],
    scene_index: int,
    label_map: dict[str, str | None],
) -> dict[tuple[str, str], list[dict[str, Any]]]:
    """Recompute which NER spans merged into each (type, match key) candidate.

    `extract` keeps only the first display value and the max score, so the label that
    actually produced a candidate is not in the output. Replaying the map here is what
    lets a reviewer see `ALT -> event` rather than just `event`.
    """
    spans: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for text in texts:
        for span in predictions[(scene_index, text["label"])]:
            tag_type = label_map.get(span["label"])
            if tag_type is None:
                continue
            value = text["text"][span["start"] : span["end"]]
            spans[(tag_type, match_key(value))].append(
                {
                    "nerLabel": span["label"],
                    "from": text["label"],
                    "surface": value,
                    "score": round(span["confidence"], 4),
                }
            )
    return spans


def build(run: Path, out: Path) -> dict[str, Any]:
    config = load_config()
    label_map = config.label_map
    summary = read(run / "summary.json")
    scenes_out = []
    for record in summary["clips"]:
        clip = record["clip"]
        inputs = read(run / clip / "inputs.json")
        bundle = read(run / clip / "output.json")
        raw = read(run / clip / "predictions.json")
        predictions: dict[tuple[int, str], list[dict[str, Any]]] = {
            (p["sceneIndex"], p["label"]): p["spans"] for p in raw
        }
        by_scene = {scene["scene_index"]: scene for scene in inputs}
        for scene in bundle["output"]["scenes"]:
            index = scene["sceneIndex"]
            texts = by_scene[index]["texts"]
            spans = contributing_spans(texts, predictions, index, label_map)
            # 근거 참조를 입력 텍스트 라벨로 되돌린다. 사람은 storageKey 가 아니라
            # "어느 문장/어느 화면 글자에서 나왔나"로 읽는다.
            label_of = {json.dumps(t["evidence"], sort_keys=True): t["label"] for t in texts}
            sheet_texts = []
            for text in texts:
                item = {
                    "label": text["label"],
                    "kind": text["evidence"]["sourceRefType"],
                    "text": text["text"],
                }
                if text["evidence"]["sourceRefType"] == "ocr_observation":
                    item["frame"] = frame_path(clip, text["evidence"]["storageKey"])
                    item["observationIndex"] = text["evidence"]["observationIndex"]
                sheet_texts.append(item)
            candidates = []
            for position, candidate in enumerate(scene["tagCandidates"]):
                key = (candidate["type"], match_key(candidate["value"]))
                origins = [label_of[json.dumps(e, sort_keys=True)] for e in candidate["evidence"]]
                entry = {
                    "id": f"{clip}#{index}#{position}",
                    "type": candidate["type"],
                    "value": candidate["value"],
                    "source": candidate["source"],
                    "confidence": candidate["confidence"],
                    "from": origins,
                    "spans": spans.get(key, []),
                    "verdict": None,
                    "goldType": None,
                    # 대사는 제공 샘플 자막을 그대로 받은 것이라 이 검수에서 다시 듣지
                    # 않는다. 화면 대조가 필요한 것은 OCR 유래 후보뿐이다.
                    "screenTruth": (
                        "faithful" if all(o.startswith("tr_") for o in origins) else None
                    ),
                    "note": "",
                }
                candidates.append(entry)
            scenes_out.append(
                {
                    "clip": clip,
                    "sceneIndex": index,
                    "texts": sheet_texts,
                    "candidates": candidates,
                    "missed": [],
                    "missedReviewed": False,
                }
            )
    sheet = {
        "schema": "npick.entity-extraction.gold-review/v1",
        "run": str(run).replace("\\", "/"),
        "identity": summary["identity"],
        "stageVersion": summary["stageVersion"],
        "corpusSha256": summary["corpusSha256"],
        "labeledBy": "",
        "howTo": [
            "candidates[].verdict 에 verdicts 중 하나를 적는다. 비워두면 미검수로 센다.",
            "판단 기준은 texts 에 적힌 문자열이다. 화면 실제와의 차이는 "
            "screenTruth 로 따로 적는다.",
            "type_wrong 이면 goldType 에 맞는 유형을 적는다.",
            "OCR 유래 후보는 texts[].frame 키프레임을 열고 screenTruth 를 "
            "faithful/misread 로 바꾼다.",
            "missed 에는 그 장면의 texts 를 읽고 후보로 나왔어야 하는 개체를 적는다: "
            '{"type": ..., "value": ..., "from": "ocr_3", "upstream": false, "note": ""}.',
            "화면에는 보이는데 OCR 이 그 글자를 못 준 개체는 upstream=true 로 적는다. "
            "이 단계가 볼 수 없었던 것이라 단계 재현율 분모에서 빠지고 실물 재현율에만 들어간다.",
            "missed 를 다 훑었으면 missedReviewed 를 true 로 바꾼다. 재현율 분모는 이 장면만 센다.",
        ],
        "verdicts": VERDICTS,
        "screenTruth": SCREEN_TRUTH,
        "types": list(ENTITY_TYPES),
        "scenes": scenes_out,
    }
    write(out, sheet)
    return sheet


def band(confidence: float) -> str:
    for edge, name in ((0.5, "[0.0, 0.5)"), (0.7, "[0.5, 0.7)"), (0.9, "[0.7, 0.9)")):
        if confidence < edge:
            return name
    return "[0.9, 1.0]"


def kind_of(candidate: dict[str, Any]) -> str:
    labels = candidate["from"]
    if all(label.startswith("ocr_") for label in labels):
        return "ocr"
    if all(label.startswith("tr_") for label in labels):
        return "transcript"
    return "mixed"


def ratio(top: int, bottom: int) -> float | None:
    return round(top / bottom, 4) if bottom else None


def score(sheet: dict[str, Any]) -> dict[str, Any]:
    """Candidate-level precision, entity-level recall over the scenes marked reviewed.

    Three precision figures, and they answer different questions. `strict` is this stage
    judged on the text it was handed. `attributable` drops candidates whose input was not
    language at all, because no choice inside this stage turns OCR noise into a right
    answer — that signal is `ocr_observation.confidence` upstream. `endToEnd` is the only
    one a tag consumer feels: right entity **and** the source text matched the screen.
    """
    verdicts: Counter[str] = Counter()
    truth: Counter[str] = Counter()
    by_type: dict[str, Counter[str]] = defaultdict(Counter)
    by_kind: dict[str, Counter[str]] = defaultdict(Counter)
    by_band: dict[str, Counter[str]] = defaultdict(Counter)
    end_to_end_ok = 0
    pending_truth = 0
    unreviewed = []
    found: set[tuple[str, int, str, str]] = set()
    gold: set[tuple[str, int, str, str]] = set()
    end_to_end_gold: set[tuple[str, int, str, str]] = set()
    missed_by_type: Counter[str] = Counter()
    upstream_missed = 0
    recall_scenes = 0
    for scene in sheet["scenes"]:
        where = (scene["clip"], scene["sceneIndex"])
        for candidate in scene["candidates"]:
            verdict = candidate["verdict"]
            if verdict is None:
                unreviewed.append(candidate["id"])
                continue
            if verdict not in VERDICTS:
                raise ValueError(f"unknown verdict {verdict!r} on {candidate['id']}")
            verdicts[verdict] += 1
            seen = candidate["screenTruth"] or "unknown"
            if seen not in SCREEN_TRUTH:
                raise ValueError(f"unknown screenTruth {seen!r} on {candidate['id']}")
            truth[seen] += 1
            if verdict == "correct":
                # 화면 대조가 결과를 바꾸는 것은 '맞다'고 판정한 후보뿐이다. 나머지는
                # 화면이 어떻든 이미 틀렸으므로 미확인이어도 분모에 그대로 남는다.
                end_to_end_ok += seen == "faithful"
                pending_truth += seen == "unknown"
            by_type[candidate["type"]][verdict] += 1
            by_kind[kind_of(candidate)][verdict] += 1
            by_band[band(candidate["confidence"])][verdict] += 1
            if not scene["missedReviewed"]:
                continue
            key = match_key(candidate["value"])
            if verdict == "correct":
                found.add((*where, candidate["type"], key))
                gold.add((*where, candidate["type"], key))
                end_to_end_gold.add((*where, candidate["type"], key))
            elif verdict == "type_wrong":
                if not candidate["goldType"]:
                    raise ValueError(f"type_wrong without goldType on {candidate['id']}")
                gold.add((*where, candidate["goldType"], key))
                end_to_end_gold.add((*where, candidate["goldType"], key))
        if not scene["missedReviewed"]:
            continue
        recall_scenes += 1
        for miss in scene["missed"]:
            entry = (*where, miss["type"], match_key(miss["value"]))
            end_to_end_gold.add(entry)
            missed_by_type[miss["type"]] += 1
            if miss.get("upstream"):
                # 화면에만 있고 OCR 이 넘겨주지 않은 개체. 이 단계는 존재 자체를 볼 수
                # 없었으므로 단계 재현율의 분모가 아니다. 파이프라인 재현율에는 남는다.
                upstream_missed += 1
                continue
            gold.add(entry)
    reviewed = sum(verdicts.values())
    readable = reviewed - verdicts["unreadable_source"]
    return {
        "schema": "npick.entity-extraction.gold-metrics/v1",
        "run": sheet["run"],
        "identity": sheet["identity"],
        "stageVersion": sheet["stageVersion"],
        "corpusSha256": sheet["corpusSha256"],
        "labeledBy": sheet["labeledBy"],
        "coverage": {
            "scenes": len(sheet["scenes"]),
            "recallScenes": recall_scenes,
            "candidates": reviewed + len(unreviewed),
            "reviewed": reviewed,
            "unreviewed": len(unreviewed),
        },
        "verdicts": dict(verdicts),
        "screenTruth": dict(truth),
        "precision": {
            "strict": ratio(verdicts["correct"], reviewed),
            "attributable": ratio(verdicts["correct"], readable),
            "attributableDenominator": readable,
            "endToEnd": ratio(end_to_end_ok, reviewed - pending_truth),
            "endToEndDenominator": reviewed - pending_truth,
        },
        "recall": {
            "stage": ratio(len(found), len(gold)),
            "stageGold": len(gold),
            "endToEnd": ratio(len(found), len(end_to_end_gold)),
            "endToEndGold": len(end_to_end_gold),
            "found": len(found),
            "upstreamMissed": upstream_missed,
            "missedByType": dict(missed_by_type),
        },
        "byType": {t: dict(c) for t, c in sorted(by_type.items())},
        "bySourceKind": {k: dict(c) for k, c in sorted(by_kind.items())},
        "byConfidenceBand": {b: dict(by_band[b]) for b in sorted(by_band)},
        "unreviewed": unreviewed[:50],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    builder = sub.add_parser("build", help="saved run -> empty review sheet")
    builder.add_argument("--run", type=Path, required=True)
    builder.add_argument("--out", type=Path, required=True)
    scorer = sub.add_parser("score", help="filled review sheet -> precision/recall")
    scorer.add_argument("--sheet", type=Path, required=True)
    scorer.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "build":
        sheet = build(args.run, args.out)
        print(json.dumps({"scenes": len(sheet["scenes"]), "out": str(args.out)}))
        return
    metrics = score(read(args.sheet))
    write(args.out, metrics)
    print(json.dumps(metrics["coverage"], ensure_ascii=False))


if __name__ == "__main__":
    main()
