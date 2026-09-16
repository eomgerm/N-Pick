"""Replay the existing sports corpus through the real local NER and pure extractor.

Run from ai: python -m npick_worker.entity_extraction.report --corpus PATH --out PATH
This is a sample harness, not a job adapter. Its transcript snapshot is generated from
the supplied sample corpus, whose source is the human-provided sample subtitles.
"""

import argparse
import hashlib
import json
import time
from collections import defaultdict
from pathlib import Path
from typing import Any

from npick_worker.entity_extraction.config import load_config
from npick_worker.entity_extraction.extractor import SceneInput, TextInput, extract, match_key
from npick_worker.entity_extraction.local_ner import LocalNer
from npick_worker.entity_extraction.schema import Evidence, OcrEvidence, Output, TranscriptEvidence


def write(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def archive(output_dir: Path, destination: Path) -> None:
    """Validate saved outputs against their saved inputs and retain a compact review artifact."""
    summary = json.loads((output_dir / "summary.json").read_text(encoding="utf-8"))
    files = {}
    examples = []
    count = 0
    for record in summary["clips"]:
        clip = record["clip"]
        inputs = json.loads((output_dir / clip / "inputs.json").read_text(encoding="utf-8"))
        bundle = json.loads((output_dir / clip / "output.json").read_text(encoding="utf-8"))
        output = Output.model_validate_json(json.dumps(bundle["output"]))
        by_scene = {scene["scene_index"]: scene for scene in inputs}
        seen_types = set()
        for scene in output.scenes:
            for candidate in scene.tagCandidates:
                count += 1
                evidence_texts = []
                for ref in candidate.evidence:
                    matches = [
                        t
                        for t in by_scene[scene.sceneIndex]["texts"]
                        if t["evidence"] == ref.model_dump()
                    ]
                    if not matches or not any(
                        match_key(candidate.value) in match_key(t["text"]) for t in matches
                    ):
                        raise ValueError("saved candidate has no matching source text")
                    evidence_texts.append(matches[0]["text"])
                if candidate.type not in seen_types:
                    seen_types.add(candidate.type)
                    examples.append(
                        {
                            "clip": clip,
                            "sceneIndex": scene.sceneIndex,
                            "candidate": candidate.model_dump(mode="json"),
                            "sourceTexts": evidence_texts,
                        }
                    )
        for file in sorted((output_dir / clip).glob("*.json")):
            files[f"{clip}/{file.name}"] = hashlib.sha256(file.read_bytes()).hexdigest()
    write(
        destination,
        {
            "schema": "npick.entity-extraction.sample-review/v1",
            "summary": summary,
            "totalScenes": sum(record["scenes"] for record in summary["clips"]),
            "totalCandidates": count,
            "totalInferenceSeconds": round(
                sum(record["seconds"] for record in summary["clips"]), 3
            ),
            "evidenceResolution": "all candidates checked against saved scene inputs",
            "artifactsSha256": files,
            "examples": examples,
        },
    )


def run(corpus_path: Path, output_dir: Path) -> dict[str, Any]:
    import torch

    corpus_bytes = corpus_path.read_bytes()
    corpus = json.loads(corpus_bytes)
    config = load_config()
    ner = LocalNer(config)
    torch.cuda.reset_peak_memory_stats()
    load_start = time.perf_counter()
    ner.load()
    load_seconds = time.perf_counter() - load_start
    clips: dict[str, list[Any]] = defaultdict(list)
    for scene in corpus["scenes"]:
        clips[scene["clip"]].append(scene)
    records = []
    for clip, raw_scenes in clips.items():
        scenes = []
        snapshots: dict[str, Any] = {}
        for raw in raw_scenes:
            texts = []
            index = raw["sceneIndex"]
            for item in raw["texts"]:
                ref: Evidence
                if item["kind"] == "ocr":
                    ref = OcrEvidence(
                        sourceRefType="ocr_observation",
                        sceneIndex=index,
                        timestampMs=item["timestampMs"],
                        storageKey=item["storageKey"],
                        observationIndex=item["observationIndex"],
                    )
                else:
                    segment_id = hashlib.sha256(
                        json.dumps(
                            [item["startMs"], item["endMs"], item["text"]],
                            ensure_ascii=False,
                        ).encode()
                    ).hexdigest()[:16]
                    snapshots[segment_id] = {
                        "id": segment_id,
                        "s": item["startMs"],
                        "e": item["endMs"],
                        "t": item["text"],
                        "sourceDetail": "uploaded",
                    }
                    ref = TranscriptEvidence(
                        sourceRefType="scene",
                        sceneIndex=index,
                        storageKey=f"{clip}/transcript.json",
                        segmentId=segment_id,
                        s=item["startMs"],
                        e=item["endMs"],
                        sourceDetail="uploaded",
                    )
                texts.append(TextInput(label=item["id"], text=item["text"], evidence=ref))
            scenes.append(SceneInput(scene_index=index, texts=tuple(texts)))
        started = time.perf_counter()
        predictions = {
            (s.scene_index, t.label): ner.predict(t.text) for s in scenes for t in s.texts
        }
        result = extract(
            scenes,
            predictions,
            label_map=config.label_map,
            minimum_confidence=config.minimum_confidence,
        )
        seconds = time.perf_counter() - started
        write(output_dir / clip / "transcript.json", {"segments": list(snapshots.values())})
        write(output_dir / clip / "inputs.json", [s.model_dump(mode="json") for s in scenes])
        write(
            output_dir / clip / "output.json",
            {
                "outputSchemaVersion": "npick.stage.entity_extraction.output/v1",
                "stageVersion": ner.stage_version,
                "identity": ner.identity,
                "output": result.model_dump(mode="json"),
            },
        )
        write(
            output_dir / clip / "predictions.json",
            [
                {"sceneIndex": index, "label": label, "spans": [v.model_dump() for v in spans]}
                for (index, label), spans in predictions.items()
            ],
        )
        records.append(
            {
                "clip": clip,
                "scenes": len(scenes),
                "seconds": round(seconds, 3),
                "candidates": sum(len(s.tagCandidates) for s in result.scenes),
            }
        )
        print(json.dumps(records[-1]), flush=True)
    report = {
        "identity": ner.identity,
        "stageVersion": ner.stage_version,
        "corpusSha256": hashlib.sha256(corpus_bytes).hexdigest(),
        "device": torch.cuda.get_device_name(),
        "loadSeconds": round(load_seconds, 3),
        "peakAllocatedBytes": torch.cuda.max_memory_allocated(),
        "peakReservedBytes": torch.cuda.max_memory_reserved(),
        "clips": records,
        "limitations": [
            "No human entity gold labels; no accuracy claim",
            "Supplied sample subtitles, not a new ASR run",
            "VLM merge is covered separately; this corpus contains text only",
            "No backend persistence or pipeline end-to-end test",
        ],
    }
    write(output_dir / "summary.json", report)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    run(args.corpus, args.out)


if __name__ == "__main__":
    main()
