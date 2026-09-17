"""Build a scene-level text corpus from the 10-clip sample set, then compare entity extractors.

The corpus is what stage 8 `entity_extraction` would actually see: merged OCR text groups
(S15P21A501-95) plus the scene's transcript (overlap rule from the job contract). Each text
carries a stable id so a candidate can be pointed back at its source — the axis that decides
whether a method can fill `tag_evidence.source_ref_type`/`source_ref_id` at all.
"""

import argparse
import json
import re
import time
import unicodedata
from pathlib import Path
from typing import Any

# `tag.tag_type` 11종 중 이 단계가 낼 수 있는 개체 유형. 분류 3종은 VLM 이 내고,
# 날짜 2종은 OCR 신뢰도·원본 문맥 확인을 거친 판정이라 여기서 만들지 않는다 (FRD F-04).
ENTITY_TYPES = ("person", "organization", "location", "facility", "keyword", "event")

CLIPS = (
    "KNI_00001",
    "KNI_00282",
    "KNI_00563",
    "KNI_00845",
    "KNI_01126",
    "KNI_01407",
    "KNI_01688",
    "KNI_01970",
    "KNI_02252",
    "KNI_02533",
)


def read(path: str | Path) -> Any:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path: str | Path, value: Any) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def overlaps(a_start: int, a_end: int, b_start: int, b_end: int) -> int:
    """Half-open interval overlap — the job contract's association rule."""
    return max(a_start, b_start) < min(a_end, b_end)


def build_corpus(root: Path, out: Path) -> dict[str, Any]:
    merge = root / "ai/samples/out/ocr-merge-sports-10-20260915"
    ocr = root / "ai/samples/out/ocr-sports-10-20260915"
    labels = root / "ai/samples/TL_29. 스포츠뉴스"
    scenes_out = []
    for clip in CLIPS:
        merged = read(merge / clip / "output-v2.json")
        spans = read(ocr / clip / "scenes.json")["scenes"]
        label = read(labels / f"{clip}.json")
        obs = merged["observations"]
        for span in spans:
            index = span["scene_index"]
            start, end = span["start_time_ms"], span["end_time_ms"]
            texts = []
            for group in merged["textGroups"]:
                if group["sceneIndex"] != index:
                    continue
                rep = obs[group["representativeIndex"]]
                raw = rep["rawText"].strip()
                if len(raw) < 2:
                    # 한 글자 관측은 개체가 될 수 없다. 원본 배열에는 그대로 남는다.
                    continue
                texts.append(
                    {
                        "id": f"ocr_{len(texts)}",
                        "kind": "ocr",
                        "text": raw,
                        "confidence": rep["confidence"],
                        "unverified": rep["unverified"],
                        "observationIndex": group["representativeIndex"],
                        "storageKey": rep["storageKey"],
                        "timestampMs": rep["timestampMs"],
                    }
                )
            for term in label["video"]["term"]:
                t_start, t_end = round(term["start"] * 1000), round(term["end"] * 1000)
                if not overlaps(start, end, t_start, t_end):
                    continue
                texts.append(
                    {
                        "id": f"tr_{sum(t['kind'] == 'transcript' for t in texts)}",
                        "kind": "transcript",
                        "text": term["transcription"].strip(),
                        "startMs": t_start,
                        "endMs": t_end,
                        "speakerId": term["speaker_id"],
                    }
                )
            if texts:
                scenes_out.append(
                    {
                        "clip": clip,
                        "sceneIndex": index,
                        "startMs": start,
                        "endMs": end,
                        "texts": texts,
                    }
                )
    corpus = {
        "schema": "npick.entity-corpus/v1",
        "source": {
            "ocrMerge": "ai/samples/out/ocr-merge-sports-10-20260915 (S15P21A501-95)",
            "scenes": "ai/samples/out/ocr-sports-10-20260915/*/scenes.json",
            "transcript": "ai/samples/TL_29. 스포츠뉴스 (사람이 적은 발화. 제공 자막 자리)",
            "associationRule": "half-open overlap: max(start) < min(end)",
        },
        "scenes": scenes_out,
    }
    write(out, corpus)
    ocr_n = sum(sum(t["kind"] == "ocr" for t in s["texts"]) for s in scenes_out)
    tr_n = sum(sum(t["kind"] == "transcript" for t in s["texts"]) for s in scenes_out)
    print(f"scenes={len(scenes_out)} ocrTexts={ocr_n} transcriptTexts={tr_n} -> {out}")


def _spans(needle: str, haystack: str) -> list[tuple[int, int]]:
    """Every char span of `needle` in `haystack`. Empty means the value is not in the source."""
    return [(m.start(), m.end()) for m in re.finditer(re.escape(needle), haystack)]


def run_kiwi(corpus: dict[str, Any], root: Path) -> dict[str, Any]:
    """Baseline: Kiwi proper nouns. Spans are free; the type is not."""
    import sys

    sys.path.insert(0, str(root / "ai/src"))
    from kiwipiepy import Kiwi

    from npick_worker.korean_tokens import engine_version
    from npick_worker.query_normalization.config import get_default_config

    settings = get_default_config()
    kiwi = Kiwi()
    for word in settings.user_words:
        kiwi.add_user_word(word, "NNP", settings.user_word_score)

    started = time.perf_counter()
    rows = []
    for scene in corpus["scenes"]:
        for text in scene["texts"]:
            # NFKC only — no casefold, so offsets stay valid against the source string.
            source = unicodedata.normalize("NFKC", text["text"])
            for token in kiwi.tokenize(source):
                if token.tag != "NNP" or len(token.form) < 2:
                    continue
                rows.append(
                    {
                        "clip": scene["clip"],
                        "sceneIndex": scene["sceneIndex"],
                        "textId": text["id"],
                        "kind": text["kind"],
                        "value": source[token.start : token.start + token.len],
                        "type": None,  # Kiwi 는 유형을 모른다. tag_type 을 채울 수 없다.
                        "confidence": None,
                        "span": [token.start, token.start + token.len],
                    }
                )
    return {
        "method": "kiwi-nnp",
        "engine": engine_version(),
        "config": settings.version_id,
        "seconds": round(time.perf_counter() - started, 2),
        "device": "cpu",
        "candidates": rows,
    }


#: 공개 NER 라벨 → `tag.tag_type`. 어긋나는 것이 이 방식의 비용이다.
NER_TYPE_MAP = {
    "PS": "person",
    "PER": "person",
    "OG": "organization",
    "ORG": "organization",
    "LC": "location",
    "LOC": "location",
    "AF": "facility",  # 모두의말뭉치 AF = 인공물. facility 와 부분적으로만 겹친다.
    "FD": "keyword",
    "TR": "keyword",
    "CV": "keyword",
    "EV": "event",
    "AM": "keyword",
    "PT": "keyword",
    "TM": "keyword",
    "MT": "keyword",
}

#: KPF 체계는 150 태그를 접두로 쪼갠다 (`OGG_ECONOMY`, `LCP_CITY`, `AFA_DOCUMENT`).
#: 긴 접두부터 본다 — `AFW` 가 `AF` 보다 먼저 걸려야 한다.
NER_PREFIX_MAP = (
    ("PS", "person"),
    ("OGG", "organization"),
    ("OG", "organization"),
    ("LCP", "location"),
    ("LCG", "location"),
    ("LC", "location"),
    ("AFA", "facility"),
    ("AFW", "facility"),
    ("AF", "facility"),
    ("EV", "event"),
    ("CV", "keyword"),
    ("TR", "keyword"),
    ("FD", "keyword"),
    ("TM", "keyword"),
    ("AM", "keyword"),
    ("PT", "keyword"),
    ("MT", "keyword"),
)


def map_label(label: str) -> str | None:
    if label in NER_TYPE_MAP:
        return NER_TYPE_MAP[label]
    for prefix, mapped in NER_PREFIX_MAP:
        if label.startswith(prefix):
            return mapped
    return None


def fetch_labels(url: str, cache: Path) -> list[str]:
    """Recover an external BIO label table.

    KPF-bert-ner ships `config.json` without `id2label`, so the pipeline emits `LABEL_n`
    and cannot tell which id is `O`. The table lives in the training repo, not the model
    repo — that dependency is itself a finding, so the fetched file is kept next to the run.
    """
    import urllib.request

    cache = Path(cache)
    if not cache.exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(urllib.request.urlopen(url, timeout=30).read())
    text = cache.read_text(encoding="utf-8")
    block = text.split("labels = [", 1)[1].split("]", 1)[0]
    return [
        m.group(1)
        for line in block.splitlines()
        if not line.strip().startswith("#")
        for m in [re.search(r"'([^']+)'", line)]
        if m
    ]


def run_ner(
    corpus: dict[str, Any],
    model_id: str,
    device: str,
    label_url: str | None = None,
    out: Path | None = None,
) -> dict[str, Any]:
    import torch
    from transformers import AutoModelForTokenClassification, AutoTokenizer, pipeline

    tokenizer = AutoTokenizer.from_pretrained(model_id)
    model = AutoModelForTokenClassification.from_pretrained(model_id)
    if label_url:
        labels = fetch_labels(label_url, Path(out) / "external-labels.py")
        if len(labels) != model.config.num_labels:
            raise SystemExit(f"label table {len(labels)} != num_labels {model.config.num_labels}")
        model.config.id2label = dict(enumerate(labels))
        model.config.label2id = {v: k for k, v in model.config.id2label.items()}
    ner = pipeline(
        "ner",
        model=model,
        tokenizer=tokenizer,
        aggregation_strategy="simple",
        device=0 if device == "cuda" and torch.cuda.is_available() else -1,
    )
    started = time.perf_counter()
    rows = []
    unmapped = {}
    for scene in corpus["scenes"]:
        for text in scene["texts"]:
            source = unicodedata.normalize("NFKC", text["text"])
            for ent in ner(source):
                label = ent["entity_group"].split("-")[-1].upper()
                mapped = map_label(label)
                if mapped is None:
                    unmapped[label] = unmapped.get(label, 0) + 1
                value = source[ent["start"] : ent["end"]].strip()
                if len(value) < 2:
                    continue
                rows.append(
                    {
                        "clip": scene["clip"],
                        "sceneIndex": scene["sceneIndex"],
                        "textId": text["id"],
                        "kind": text["kind"],
                        "value": value,
                        "type": mapped,
                        "rawLabel": label,
                        "confidence": round(float(ent["score"]), 4),
                        "span": [int(ent["start"]), int(ent["end"])],
                    }
                )
    return {
        "method": f"ner:{model_id}",
        "engine": f"transformers{__import__('transformers').__version__}",
        "seconds": round(time.perf_counter() - started, 2),
        "device": ner.device.type,
        "unmappedLabels": dict(sorted(unmapped.items(), key=lambda kv: -kv[1])),
        "candidates": rows,
    }


PROMPT = """\
다음은 뉴스 영상 한 장면에서 얻은 텍스트다. 화면 글자(ocr)와 발화(transcript)가 섞여 있다.
여기에 실제로 등장하는 개체만 뽑아라. 없으면 빈 배열을 반환한다.

유형은 다음 중 하나만 쓴다: person, organization, location, facility, keyword, event
- person: 사람 이름
- organization: 팀·기업·기관
- location: 행정구역·지명
- facility: 경기장·건물 등 시설
- event: 대회·사건
- keyword: 위에 없지만 이 장면을 찾는 데 쓸 검색어

규칙:
- value 는 반드시 아래 텍스트에 **그대로 나오는 문자열**이어야 한다. 고쳐 쓰거나 풀어 쓰지 않는다.
- sourceId 는 그 값이 나온 텍스트의 id 다.
- 추측해서 만들지 않는다. 텍스트에 없는 것은 넣지 않는다.

출력은 JSON 하나다:
{{"entities": [{{"type": "...", "value": "...", "sourceId": "...", "confidence": 0.0}}]}}

텍스트:
{block}
"""


def run_llm(
    corpus: dict[str, Any], model_id: str, device: str, max_new_tokens: int
) -> dict[str, Any]:
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(model_id)
    model = AutoModelForCausalLM.from_pretrained(
        model_id,
        dtype=torch.bfloat16 if device == "cuda" else torch.float32,
    )
    model.to(device)
    model.eval()
    started = time.perf_counter()
    rows = []
    failures = []
    for scene in corpus["scenes"]:
        block = "\n".join(f"[{t['id']}] {t['text']}" for t in scene["texts"])
        messages = [{"role": "user", "content": PROMPT.format(block=block)}]
        prompt = tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=True, enable_thinking=False
        )
        inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
        with torch.inference_mode():
            generated = model.generate(
                **inputs,
                max_new_tokens=max_new_tokens,
                do_sample=False,
                temperature=None,
                top_p=None,
                top_k=None,
                pad_token_id=tokenizer.eos_token_id,
            )
        raw = tokenizer.decode(
            generated[0][inputs["input_ids"].shape[1] :], skip_special_tokens=True
        )
        match = re.search(r"\{.*\}", raw, re.S)
        if not match:
            failures.append(
                {
                    "clip": scene["clip"],
                    "sceneIndex": scene["sceneIndex"],
                    "reason": "no-json",
                    "raw": raw[:300],
                }
            )
            continue
        try:
            parsed = json.loads(match.group(0))
        except json.JSONDecodeError as exc:
            failures.append(
                {
                    "clip": scene["clip"],
                    "sceneIndex": scene["sceneIndex"],
                    "reason": f"json:{exc}",
                    "raw": raw[:300],
                }
            )
            continue
        by_id = {t["id"]: unicodedata.normalize("NFKC", t["text"]) for t in scene["texts"]}
        for ent in parsed.get("entities") or []:
            if not isinstance(ent, dict):
                continue
            value = str(ent.get("value", "")).strip()
            if len(value) < 2:
                continue
            claimed = str(ent.get("sourceId", ""))
            # 모델이 말한 출처에서 먼저 찾고, 없으면 같은 장면의 다른 텍스트에서 찾는다.
            span, source_id = None, None
            if claimed in by_id:
                found = _spans(unicodedata.normalize("NFKC", value), by_id[claimed])
                if found:
                    span, source_id = list(found[0]), claimed
            if span is None:
                for tid, text in by_id.items():
                    found = _spans(unicodedata.normalize("NFKC", value), text)
                    if found:
                        span, source_id = list(found[0]), tid
                        break
            kind = next((t["kind"] for t in scene["texts"] if t["id"] == source_id), None)
            rows.append(
                {
                    "clip": scene["clip"],
                    "sceneIndex": scene["sceneIndex"],
                    "textId": source_id,
                    "claimedTextId": claimed or None,
                    "kind": kind,
                    "value": value,
                    "type": ent.get("type") if ent.get("type") in ENTITY_TYPES else None,
                    "rawLabel": ent.get("type"),
                    "confidence": ent.get("confidence"),
                    "span": span,  # None = 원문에서 되찾지 못함 = 근거를 걸 수 없음
                }
            )
    return {
        "method": f"llm:{model_id}",
        "engine": f"transformers{__import__('transformers').__version__}",
        "seconds": round(time.perf_counter() - started, 2),
        "device": str(model.device),
        "scenesFailed": failures,
        "candidates": rows,
    }


def enrich(result: dict[str, Any], corpus: dict[str, Any]) -> dict[str, Any]:
    """Per-source-kind counts and the share that rests on low-confidence OCR."""
    index = {
        (scene["clip"], scene["sceneIndex"], text["id"]): text
        for scene in corpus["scenes"]
        for text in scene["texts"]
    }
    rows = result["candidates"]
    kinds = {}
    unverified = 0
    for row in rows:
        kinds[row.get("kind")] = kinds.get(row.get("kind"), 0) + 1
        source = index.get((row["clip"], row["sceneIndex"], row.get("textId")))
        if source and source.get("unverified"):
            unverified += 1
    return {
        "fromOcr": kinds.get("ocr", 0),
        "fromTranscript": kinds.get("transcript", 0),
        "onUnverifiedOcr": unverified,
        "onUnverifiedOcrRatio": round(unverified / len(rows), 3) if rows else 0.0,
    }


def summarize(result: dict[str, Any]) -> dict[str, Any]:
    rows = result["candidates"]
    grounded = [r for r in rows if r.get("span")]
    typed = [r for r in rows if r.get("type")]
    unique = {(r["value"], r.get("type")) for r in rows}
    claimed_ok = [
        r for r in rows if r.get("claimedTextId") and r["claimedTextId"] == r.get("textId")
    ]
    has_claim = [r for r in rows if r.get("claimedTextId")]
    return {
        "method": result["method"],
        "device": result.get("device"),
        "seconds": result["seconds"],
        "candidates": len(rows),
        "uniqueValues": len(unique),
        "groundedRatio": round(len(grounded) / len(rows), 3) if rows else 0.0,
        "typedRatio": round(len(typed) / len(rows), 3) if rows else 0.0,
        "sourceIdCorrect": round(len(claimed_ok) / len(has_claim), 3) if has_claim else None,
        "scenesFailed": len(result.get("scenesFailed", [])),
        "unmappedLabels": result.get("unmappedLabels") or None,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("corpus", "kiwi", "ner", "llm", "report"))
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model")
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--max-new-tokens", type=int, default=512)
    parser.add_argument(
        "--label-url", help="외부 BIO 라벨표 URL (config.json 에 id2label 이 없는 모델용)"
    )
    args = parser.parse_args()

    out = args.out.resolve()
    corpus_path = out / "corpus.json"
    if args.command == "corpus":
        build_corpus(args.repo.resolve(), corpus_path)
        return

    if args.command == "report":
        corpus = read(corpus_path)
        results = [read(p) for p in sorted(out.glob("result-*.json"))]
        rows = [summarize(r) | enrich(r, corpus) for r in results]
        typed_values = {
            r["method"]: {(c["value"], c["type"]) for c in r["candidates"] if c.get("type")}
            for r in results
        }
        # 값만으로도 한 번 잰다 — Kiwi 는 유형이 없어 (value, type) 비교에서 항상 0 이 된다.
        plain_values = {r["method"]: {c["value"] for c in r["candidates"]} for r in results}
        overlap = {}
        names = sorted(typed_values)
        for i, a in enumerate(names):
            for b in names[i + 1 :]:
                union = typed_values[a] | typed_values[b]
                plain_union = plain_values[a] | plain_values[b]
                overlap[f"{a} ∩ {b}"] = {
                    "sharedTyped": len(typed_values[a] & typed_values[b]),
                    "jaccardTyped": round(len(typed_values[a] & typed_values[b]) / len(union), 3)
                    if union
                    else 0.0,
                    "sharedValues": len(plain_values[a] & plain_values[b]),
                    "jaccardValues": round(
                        len(plain_values[a] & plain_values[b]) / len(plain_union), 3
                    )
                    if plain_union
                    else 0.0,
                }
        write(out / "summary.json", {"methods": rows, "typedOverlap": overlap})
        for row in rows:
            print(json.dumps(row, ensure_ascii=False))
        for key, value in overlap.items():
            print(key, json.dumps(value, ensure_ascii=False))
        return

    corpus = read(corpus_path)
    if args.command == "kiwi":
        result = run_kiwi(corpus, args.repo.resolve())
        name = "kiwi"
    elif args.command == "ner":
        result = run_ner(corpus, args.model, args.device, args.label_url, out)
        name = "ner-" + args.model.replace("/", "--")
    else:
        result = run_llm(corpus, args.model, args.device, args.max_new_tokens)
        name = "llm-" + args.model.replace("/", "--")
    write(out / f"result-{name}.json", result)
    print(json.dumps(summarize(result), ensure_ascii=False))


if __name__ == "__main__":
    main()
