"""GPU 서버에서 후보 하나를 평가한다. 모델마다 별도 프로세스로 실행한다.

기존 report의 입력 선정·검증·실패 보존을 재사용하며 BE 없이 동작한다.
실측 설정과 입력 manifest를 먼저 저장하고, 모델 준비 실패도 실행 기록에 남긴다.
"""

import argparse
import hashlib
import json
import math
import platform
import re
import statistics
import subprocess
import time
from collections.abc import Sequence
from datetime import UTC, datetime
from importlib.metadata import distributions, version
from pathlib import Path
from typing import Any

from npick_worker import korean_tokens
from npick_worker.settings import get_settings
from npick_worker.vlm_metadata import report
from npick_worker.vlm_metadata.config import DEFAULT_CONFIG_PATH, load_config
from npick_worker.vlm_metadata.describer import select_keyframes
from npick_worker.vlm_metadata.prompt import (
    prompt_version,
    render_system_prompt,
    render_user_prompt,
)
from npick_worker.vlm_metadata.schema import SCHEMA_VERSION, RawSceneMetadata
from npick_worker.vlm_metadata.transformers_backend import build_client


def write_json(path: Path, value: object) -> None:
    """중단 시 앞선 완성 파일을 지키도록 같은 디렉터리에서 교체한다."""
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def _command(args: list[str]) -> str:
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=15, check=False)
        return result.stdout.strip() if result.returncode == 0 else "unavailable"
    except (OSError, subprocess.TimeoutExpired):
        return "unavailable"


def summarize(result: dict[str, Any], expected: int) -> dict[str, Any]:
    """schema smoke만 자동 판정한다. 시각적 정확도는 사람 검수 대상이다."""
    successes = result.get("scenes", [])
    failures = result.get("rejected", [])
    attempted = [*successes, *failures]
    times = sorted(float(row["elapsedSeconds"]) for row in attempted)
    return {
        "expectedScenes": expected,
        "attemptedScenes": len(attempted),
        "schemaValidScenes": len(successes),
        "failedScenes": len(failures),
        # '없음' 을 계약과 다른 **표기**로 쓴 장면과 그 자리 수(`normalize.py`, S15P21A501-93).
        # **smoke 판정에 넣지 않는다** — 정규화된 출력은 유효한 출력이다. 그런데도 요약에
        # 올리는 이유는 이 값이 후보를 가르는 근거이기 때문이다. 0 이 아닌 후보는 프롬프트를
        # 그대로 따르지 않았다는 뜻이고, 그건 사람이 원문을 봐야 하는 차이다.
        "normalizedScenes": sum(1 for row in successes if row.get("notations")),
        "normalizedValues": sum(len(row.get("notations", ())) for row in successes),
        # 계약의 `null` 을 한 칸 다른 자리에 써서 옮긴 장면과 자리 수. **후보를 가르는 값이
        # 아니다** — 빈 화면·전환 장면이 있으면 오르는 것이 정상이라(2026-09-16 실측) 후보
        # 사이 비교는 같은 입력일 때만 뜻이 있다.
        #
        # `notations` 가 없는 옛 기록은 갈래를 알 수 없다. 그때는 전부 이쪽으로 센다 —
        # 모르는 것을 "표기였다" 로 세면 없는 신호를 만들어 내기 때문이다.
        "reshapedScenes": sum(
            1
            for row in successes
            if len(row.get("normalizations", ())) > len(row.get("notations", ()))
        ),
        "reshapedValues": sum(
            len(row.get("normalizations", ())) - len(row.get("notations", ())) for row in successes
        ),
        "smokePassed": (
            expected >= 10
            and len(successes) == expected
            and not failures
            and all(len(row["inputs"]) >= 2 for row in successes)
        ),
        "attemptedMeanSeconds": statistics.mean(times) if times else None,
        "attemptedMedianSeconds": statistics.median(times) if times else None,
        "attemptedP95Seconds": times[math.ceil(0.95 * len(times)) - 1] if times else None,
        "timingScope": "model load excluded; first inference included; failures included",
        "memory": result.get("memory"),
        "qualityVerdict": "not_evaluated",
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frames_dir", type=Path)
    parser.add_argument("--model", required=True)
    parser.add_argument("--revision", default="main", help="시작 시 Hub commit SHA로 고정")
    parser.add_argument("--out", required=True, type=Path, help="새 실행 디렉터리; 덮어쓰기 금지")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--dtype", choices=("bfloat16", "float16"), default="bfloat16")
    parser.add_argument("--thinking", choices=("off", "on", "default"), default="off")
    parser.add_argument(
        "--memory-budget-gib",
        type=float,
        required=True,
        help="이 프로세스의 PyTorch allocator 상한; GPU 전체 격리가 아님",
    )
    args = parser.parse_args(argv)
    if args.limit < 10:
        parser.error("smoke에는 --limit 10 이상이 필요합니다")
    if not math.isfinite(args.memory_budget_gib) or args.memory_budget_gib <= 0:
        parser.error("--memory-budget-gib는 양수여야 합니다")

    config = load_config(args.config)
    scenes, paths = report.discover_scenes(args.frames_dir)
    scenes = scenes[: args.limit]
    shortfall = report._smoke_shortfall(scenes, config)
    if shortfall is not None:
        parser.error(shortfall)
    # 입력 조건 확인 뒤 디렉터리를 만든다. 기존 평가를 조용히 덮어쓰지 않는다.
    args.out.mkdir(parents=True, exist_ok=False)
    manifest = []
    for scene in scenes:
        for index, frame in enumerate(select_keyframes(scene, config), start=1):
            path = paths[frame.storage_key]
            manifest.append(
                {
                    "sceneIndex": scene.scene_index,
                    "label": f"kf_{index}",
                    "timestampMs": frame.timestamp_ms,
                    "storageKey": frame.storage_key,
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                }
            )
    write_json(args.out / "inputs.json", manifest)
    (args.out / "config.toml").write_bytes(args.config.read_bytes())
    write_json(args.out / "schema.json", RawSceneMetadata.model_json_schema())
    source_root = Path(__file__).resolve().parents[1]
    source_hashes = {
        str(path.relative_to(source_root)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted((source_root / "vlm_metadata").glob("*.py"))
    }
    write_json(args.out / "source-hashes.json", source_hashes)
    runtime_config = {
        "dtype": args.dtype,
        "thinking": args.thinking,
        "memoryBudgetGiB": args.memory_budget_gib,
        "limit": args.limit,
    }
    fingerprint = hashlib.sha256(json.dumps(runtime_config, sort_keys=True).encode()).hexdigest()
    record: dict[str, Any] = {
        "runId": args.out.name,
        "startedAt": datetime.now(UTC).isoformat(),
        "status": "preparing",
        "requestedModel": args.model,
        "requestedRevision": args.revision,
        "benchmarkConfigVersion": f"vlm-benchmark/v1:{fingerprint[:16]}",
        "benchmarkConfig": runtime_config,
        "configVersion": config.version_id,
        "promptVersion": prompt_version(config),
        "schemaVersion": SCHEMA_VERSION,
        "python": platform.python_version(),
        "packages": {d.metadata["Name"]: d.version for d in distributions() if d.metadata["Name"]},
        "platform": platform.platform(),
        "gitCommit": _command(["git", "rev-parse", "HEAD"]),
        "gpuBefore": _command(
            [
                "nvidia-smi",
                "--query-gpu=index,name,driver_version,memory.total,memory.used",
                "--format=csv",
            ]
        ),
    }
    write_json(args.out / "run.json", record)
    started = time.perf_counter()
    try:
        import torch
        from huggingface_hub import HfApi

        if not torch.cuda.is_available():
            raise RuntimeError("CUDA가 보이지 않습니다. CPU로 대체 실행하지 않습니다")
        if args.dtype == "bfloat16" and not torch.cuda.is_bf16_supported():
            raise RuntimeError("이 GPU/runtime은 BF16을 지원하지 않습니다")
        total = torch.cuda.get_device_properties(0).total_memory
        budget = int(args.memory_budget_gib * 1024**3)
        if budget > total:
            raise ValueError("메모리 예산이 GPU 전체 메모리보다 큽니다")
        torch.cuda.set_per_process_memory_fraction(budget / total, device=0)
        torch.manual_seed(0)
        revision = args.revision
        if not re.fullmatch(r"[0-9a-f]{40}", revision):
            revision = HfApi().model_info(args.model, revision=revision).sha or ""
        if not re.fullmatch(r"[0-9a-f]{40}", revision):
            raise ValueError("모델 revision을 40자리 SHA로 고정하지 못했습니다")
        record.update(
            {
                "modelRevision": revision,
                "torch": torch.__version__,
                "transformers": version("transformers"),
                "cuda": torch.version.cuda,
                "tokenizer": korean_tokens.tokenizer_version(),
                "gpu": torch.cuda.get_device_name(0),
                "gpuTotalBytes": total,
            }
        )
        write_json(args.out / "run.json", record)
        client = build_client(
            args.model,
            revision=revision,
            model_dir=get_settings().vlm_model_dir,
            device_choice="cuda",
            dtype=args.dtype,
            enable_thinking={"off": False, "on": True, "default": None}[args.thinking],
        )
        load_started = time.perf_counter()
        client.warm_up()
        torch.cuda.synchronize()
        processor, model = client._ensure_loaded()
        record.update(
            {
                "loadSeconds": time.perf_counter() - load_started,
                "actualDtype": str(model.dtype),
                "modelVersion": client.model_version,
                "modelClass": type(model).__name__,
                "processorClass": type(processor).__name__,
                "attentionImplementation": getattr(model.config, "_attn_implementation", None),
                "status": "running",
            }
        )
        write_json(args.out / "run.json", record)
        write_json(args.out / "generation-config.json", model.generation_config.to_dict())
        image_processor = getattr(processor, "image_processor", None)
        if image_processor is not None:
            write_json(args.out / "image-processor.json", image_processor.to_dict())
        write_json(
            args.out / "prompts.json",
            {
                "system": render_system_prompt(config),
                "usersByFrameCount": {
                    str(n): render_user_prompt(config, n)
                    for n in sorted({len(select_keyframes(s, config)) for s in scenes})
                },
                "chatTemplate": processor.chat_template,
            },
        )
        code = report.main(
            [
                str(args.frames_dir),
                "--config",
                str(args.out / "config.toml"),
                "--out",
                str(args.out),
                "--limit",
                str(args.limit),
                "--smoke",
            ],
            client=client,
        )
        record["status"] = "succeeded" if code == 0 else "failed"
        return code
    except BaseException as exc:
        record.update({"status": "failed", "errorType": type(exc).__name__, "error": str(exc)})
        raise
    finally:
        record["finishedAt"] = datetime.now(UTC).isoformat()
        record["totalSeconds"] = time.perf_counter() - started
        write_json(args.out / "run.json", record)
        target = args.out / "vlm-metadata.json"
        if target.exists():
            result = json.loads(target.read_text(encoding="utf-8"))
            summary = summarize(result, len(scenes))
            summary["runStatus"] = record["status"]
            summary["smokePassed"] = summary["smokePassed"] and record["status"] == "succeeded"
            write_json(args.out / "summary.json", summary)
            write_json(
                args.out / "quality-review.json",
                [
                    {
                        "sceneIndex": scene.scene_index,
                        "shotTypeCorrect": None,
                        "shotTypeExpected": None,
                        "captionRating": None,
                        "sceneTypeCorrect": None,
                        "sceneTypeExpected": None,
                        "tagsGrounded": None,
                        "multiFrameUnderstanding": None,
                        "notes": "",
                    }
                    for scene in scenes
                ],
            )


if __name__ == "__main__":
    raise SystemExit(main())
