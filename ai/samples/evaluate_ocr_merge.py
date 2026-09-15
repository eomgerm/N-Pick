"""기록된 실제 OCR 관측으로 병합 설정을 비교한다. OCR 엔진은 재실행하지 않는다.

uv run --directory ai python samples/evaluate_ocr_merge.py
"""

import argparse
import hashlib
import json
import sys
import time
from dataclasses import replace
from pathlib import Path
from typing import Any

from npick_worker.jobs.models import OcrOutput
from npick_worker.ocr.merge import get_merge_config
from npick_worker.ocr.models import (
    BoundingBox,
    KeyframeObservations,
    KeyframeRef,
    OcrObservation,
    OcrResult,
)


def read_result(data: dict[str, Any]) -> OcrResult:
    keyframes = []
    for frame in data["keyframes"]:
        ref = KeyframeRef(frame["sceneIndex"], frame["timestampMs"], frame["storageKey"])
        keyframes.append(
            KeyframeObservations(
                ref,
                tuple(
                    OcrObservation(
                        keyframe=ref,
                        raw_text=obs["rawText"],
                        tokens=tuple(obs["tokens"].split()),
                        confidence=obs["confidence"],
                        unverified=obs["unverified"],
                        text_key=obs["textKey"],
                        box=BoundingBox(
                            tuple(tuple(point) for point in obs["boundingBox"]["points"])
                        ),
                    )
                    for obs in frame["observations"]
                ),
            )
        )
    return OcrResult(
        keyframes=tuple(keyframes),
        config_version=data["configVersion"],
        engine=data["engine"],
        engine_version=data["engineVersion"],
        tokenizer=data["tokenizer"],
        min_confidence=data["minConfidence"],
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("samples/out/KNI_02205-ocr/ocr.json"))
    parser.add_argument(
        "--labels", type=Path, default=Path("samples/ocr-merge-labels.KNI_02205.json")
    )
    parser.add_argument("--out", type=Path, default=Path("samples/out/ocr-merge-95"))
    args = parser.parse_args()
    raw = args.input.read_bytes()
    data = json.loads(raw)
    labels = json.loads(args.labels.read_text(encoding="utf-8"))
    digest = hashlib.sha256(raw).hexdigest()
    observations_digest = hashlib.sha256(
        json.dumps(
            data["keyframes"], ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
    ).hexdigest()
    if observations_digest != labels["observationsSha256"]:
        parser.error("입력 SHA256이 라벨과 다르다. 관측 인덱스를 재검수해야 한다.")
    result = read_result(data)
    baseline = OcrOutput.from_result(result).model_dump(mode="json", by_alias=True)["observations"]
    trials = []
    preserved: list[bool] = []
    deterministic: list[bool] = []
    args.out.mkdir(parents=True, exist_ok=True)
    for threshold in (1.0, 0.95, 0.9, 0.85, 0.8):
        config = get_merge_config().model_copy(update={"similarity_threshold": threshold})
        candidate = replace(result, merge_config=config)
        started = time.perf_counter()
        output = OcrOutput.from_result(candidate)
        elapsed_ms = (time.perf_counter() - started) * 1000
        encoded = output.model_dump_json(by_alias=True, indent=2)
        restored = OcrOutput.model_validate_json(encoded)
        # 아래 두 값이 리포트의 `allOriginalsPreserved`·`deterministicReplay` 가 되고,
        # `ai/docs/ocr.md` §4 가 그것을 기본 임계값 유지의 근거로 인용한다. assert 로 두면
        # `python -O` 에서 검사만 사라지고 단언은 남아, 확인한 적 없는 보존성을 주장하는
        # 리포트가 나온다. 그래서 검사 결과를 값으로 들고 다닌다.
        originals_kept = restored.model_dump(mode="json", by_alias=True)["observations"] == baseline
        replay_stable = output.model_dump(mode="json") == restored.model_dump(mode="json") and (
            encoded == OcrOutput.from_result(candidate).model_dump_json(by_alias=True, indent=2)
        )
        preserved.append(originals_kept)
        deterministic.append(replay_stable)
        membership = {
            index: g
            for g, group in enumerate(output.text_groups)
            for index in group.observation_indices
        }
        scores = {}
        for split, pairs in labels["splits"].items():
            tp = fp = fn = tn = 0
            for left, right, expected in pairs:
                merged = membership[left] == membership[right]
                tp += int(merged and expected)
                fp += int(merged and not expected)
                fn += int(not merged and expected)
                tn += int(not merged and not expected)
            scores[split] = {
                "truePositive": tp,
                "falsePositive": fp,
                "falseNegative": fn,
                "trueNegative": tn,
                "falseMergeRate": fp / (tp + fp) if tp + fp else None,
                "missRate": fn / (tp + fn) if tp + fn else None,
            }
        trials.append(
            {
                "threshold": threshold,
                "configVersion": config.version_id,
                "groups": len(output.text_groups),
                "elapsedMs": elapsed_ms,
                "originalsPreserved": originals_kept,
                "deterministicReplay": replay_stable,
                "scores": scores,
            }
        )
        (args.out / f"output-{threshold}.json").write_text(encoded, encoding="utf-8")
    report = {
        "inputSha256": digest,
        "observationsSha256": observations_digest,
        "observations": len(result.observations),
        "labelStatus": labels["labelStatus"],
        "trials": trials,
        "allOriginalsPreserved": all(preserved),
        "deterministicReplay": all(deterministic),
        "note": "임계값별 시간은 병합과 출력 검증만 포함. OCR 실행 및 BE 연동 시간은 제외.",
    }
    (args.out / "evaluation.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not (report["allOriginalsPreserved"] and report["deterministicReplay"]):
        # 리포트는 남긴다 — 어느 임계값에서 깨졌는지가 `trials` 에 있다.
        print("보존·재현 검사가 실패했다. evaluation.json 의 trials 를 보라.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
