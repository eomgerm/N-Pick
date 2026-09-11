"""장면 metadata 를 눈으로 확인하고 후보를 실측하는 도구.

    uv run --directory ai python -m npick_worker.vlm_metadata.report \
        samples/out/KNI_02205-frames --out samples/out/KNI_02205-vlm

입력은 `frame_extraction.report` 가 만든 디렉터리다(`s0000/kf-000004133.jpg` 구조).
운영 경로에서는 그 목록이 BE 의 `inputs.upstream.frameExtraction` 으로 온다.

이 도구가 티켓의 두 가지를 담당한다.

- **smoke test** — scene 10건 이상에 각 scene 의 복수 keyframe 을 넣어 schema 에 맞는
  출력이 나오는지 확인한다.
- **후보 비교** — 모델을 바꿔 가며(`--model`) 같은 입력으로 돌리고, 장면당 소요 시간과
  원시 출력을 남긴다. "평가에 사용한 설정 version 과 원시 결과를 보존한다" 가 티켓의
  요구이므로 `--out` 은 **원문을 그대로** 저장한다.

운영 경로가 아니다(`ocr/report.py` 와 같은 성격의 개발자 도구).
"""

import argparse
import json
import re
import sys
import time
from collections.abc import Sequence
from pathlib import Path
from typing import Final

from npick_worker.vlm_metadata.client import VlmClient, VlmModelUnavailableError
from npick_worker.vlm_metadata.config import VlmMetadataConfig, get_default_config, load_config
from npick_worker.vlm_metadata.describer import SceneDescription, describe_scene
from npick_worker.vlm_metadata.models import KeyframeRef, SceneKeyframes
from npick_worker.vlm_metadata.prompt import prompt_version
from npick_worker.vlm_metadata.validator import VlmSchemaInvalidError

#: `frame_extraction` 의 `FILE_NAME_TEMPLATE` 이 만든 이름을 되읽는다.
_FILE_NAME: Final[re.Pattern[str]] = re.compile(
    r"s(?P<scene>\d{4})[/\\]kf-(?P<timestamp>\d{9})\.jpg"
)


def discover_scenes(frames_dir: Path) -> tuple[tuple[SceneKeyframes, ...], dict[str, Path]]:
    """디렉터리에서 장면별 keyframe 묶음을 만든다.

    파일 이름이 곧 `(sceneIndex, timestampMs)` 다. `storage_key` 자리에는 디렉터리 기준
    상대 경로를 쓴다 — 운영 경로의 `keyframe.storage_key` 와 같은 역할이다.
    """
    by_scene: dict[int, list[KeyframeRef]] = {}
    paths: dict[str, Path] = {}
    for jpg in sorted(frames_dir.glob("s*/kf-*.jpg")):
        relative = jpg.relative_to(frames_dir).as_posix()
        match = _FILE_NAME.fullmatch(relative)
        if match is None:
            continue
        scene_index = int(match.group("scene"))
        by_scene.setdefault(scene_index, []).append(
            KeyframeRef(
                scene_index=scene_index,
                timestamp_ms=int(match.group("timestamp")),
                storage_key=relative,
            )
        )
        paths[relative] = jpg
    scenes = tuple(
        SceneKeyframes(scene_index=index, keyframes=tuple(keyframes))
        for index, keyframes in sorted(by_scene.items())
    )
    return scenes, paths


def render_table(rows: Sequence[tuple[SceneDescription, float]]) -> str:
    """장면마다 한 줄. 근거가 없어 비운 값은 `-` 로 보인다."""
    header = f"{'scene':>5} {'n':>2} {'초':>6} {'shot':<10} {'scene_type':<12} 설명"
    lines: list[str] = []
    for described, elapsed in rows:
        metadata = described.metadata
        scene_type = metadata.scene_type
        caption = metadata.caption
        lines.append(
            f"{metadata.scene_index:>5} "
            f"{len(described.inputs):>2} "
            f"{elapsed:>6.1f} "
            f"{metadata.shot_type.value:<10} "
            f"{(scene_type.value if scene_type is not None else '-'):<12} "
            f"{(caption.value if caption is not None else '-')}"
        )
    return "\n".join([header, "-" * len(header), *lines])


def to_json(
    rows: Sequence[tuple[SceneDescription, float]],
    config: VlmMetadataConfig,
    client: VlmClient,
) -> dict[str, object]:
    """평가 기록. **원시 출력과 설정 version 을 함께 남긴다**(티켓 요구).

    `rawOutput` 을 넣는 이유는 프롬프트를 고칠 근거가 거기에만 있기 때문이다. 검증을
    통과한 값만 남기면 "왜 이런 답이 나왔나" 를 나중에 볼 수 없다.
    """
    return {
        "versions": {
            "configVersion": config.version_id,
            "promptVersion": prompt_version(config),
            "engine": client.name,
            "engineVersion": client.version,
            "modelVersion": client.model_version,
        },
        "scenes": [
            {
                "sceneIndex": described.metadata.scene_index,
                "inputs": [
                    {"timestampMs": keyframe.timestamp_ms, "storageKey": keyframe.storage_key}
                    for keyframe in described.inputs
                ],
                "elapsedSeconds": round(elapsed, 2),
                "shotType": {
                    "value": described.metadata.shot_type.value,
                    "confidence": described.metadata.shot_type.confidence,
                },
                "caption": (
                    None
                    if described.metadata.caption is None
                    else {
                        "value": described.metadata.caption.value,
                        "confidence": described.metadata.caption.confidence,
                    }
                ),
                "tagCandidates": [
                    {"type": tag.type, "value": tag.value, "confidence": tag.confidence}
                    for tag in described.metadata.tag_candidates
                ],
                "rawOutput": described.raw_output,
            }
            for described, elapsed in rows
        ],
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="scene별 복수 keyframe으로 VLM metadata 생성")
    parser.add_argument("frames_dir", type=Path, help="frame_extraction.report 산출 디렉터리")
    parser.add_argument("--out", type=Path, help="결과 JSON 을 저장할 디렉터리")
    parser.add_argument(
        "--model",
        help="가중치 식별자. 주지 않으면 NPICK_AI_VLM_MODEL 을 쓴다 (후보 비교용)",
    )
    parser.add_argument("--revision", default="", help="가중치 리비전")
    parser.add_argument("--config", type=Path, help="설정 toml 경로 (프롬프트·어휘 실험용)")
    parser.add_argument("--limit", type=int, help="앞에서 이만큼의 장면만 처리")
    args = parser.parse_args(argv)

    config = load_config(args.config) if args.config is not None else get_default_config()
    scenes, paths = discover_scenes(args.frames_dir)
    if not scenes:
        print(f"keyframe 을 찾지 못했다: {args.frames_dir}", file=sys.stderr)
        return 1
    if args.limit is not None:
        scenes = scenes[: args.limit]

    try:
        client = _client(args.model, args.revision)
    except VlmModelUnavailableError as exc:
        # 모델이 없는 것은 이 도구의 버그가 아니다. 트레이스백 대신 무엇을 해야 하는지
        # 알려 준다 — 모델 이름은 실측 후 확정 대상이라 코드가 고르지 않는다(FRD §11).
        print(f"{exc}", file=sys.stderr)
        print(
            "  --model 로 후보를 주거나 NPICK_AI_VLM_MODEL 을 설정한다. "
            "가중치 실행에는 gpu 그룹이 필요하다: uv sync --group gpu",
            file=sys.stderr,
        )
        return 1
    print(
        f"장면 {len(scenes)}개 | {config.version_id} | {prompt_version(config)} | "
        f"{client.name} {client.version} | {client.model_version}"
    )

    rows: list[tuple[SceneDescription, float]] = []
    failures = 0
    for scene in scenes:
        started = time.perf_counter()
        try:
            described = describe_scene(scene, paths, client, config)
        except VlmSchemaInvalidError as exc:
            # 실측 도구는 멈추지 않는다. **운영 경로와 다른 점이고 의도적이다** —
            # 단계는 하나만 깨져도 전체 실패지만(`describer.describe_scenes`), 후보를
            # 비교할 때는 몇 장면이 왜 깨졌는지가 그 후보의 성적이다.
            failures += 1
            print(f"  scene {scene.scene_index}: 거부 — {exc}", file=sys.stderr)
            continue
        rows.append((described, time.perf_counter() - started))

    if rows:
        print(render_table(rows))
    total = sum(elapsed for _, elapsed in rows)
    print(
        f"성공 {len(rows)}장면 | 거부 {failures}장면 | "
        f"총 {total:.1f}초 | 장면당 평균 {total / len(rows):.1f}초"
        if rows
        else f"성공 0장면 | 거부 {failures}장면"
    )

    if args.out is not None:
        args.out.mkdir(parents=True, exist_ok=True)
        target = args.out / "vlm-metadata.json"
        target.write_text(
            json.dumps(to_json(rows, config, client), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"저장: {target}")

    # 거부가 하나라도 있으면 실패로 끝낸다. smoke test 를 CI 나 스크립트에서 돌릴 때
    # 종료 코드로 판정할 수 있어야 한다.
    return 0 if rows and failures == 0 else 1


def _client(model: str | None, revision: str) -> VlmClient:
    """후보 비교를 위해 모델을 인자로 바꿀 수 있게 한다.

    설정(`NPICK_AI_VLM_MODEL`)을 쓰지 않고 인자를 받는 이유는 이 도구의 목적이 **같은
    입력으로 여러 후보를 돌리는 것**이기 때문이다. 운영 경로는 그대로 설정을 쓴다.
    """
    from npick_worker.settings import get_settings
    from npick_worker.vlm_metadata.transformers_backend import (
        TransformersVlmClient,
        shared_client,
    )

    if model is None:
        return shared_client(get_settings())
    settings = get_settings()
    return TransformersVlmClient(
        model,
        revision=revision or "main",
        model_dir=settings.vlm_model_dir,
    )


if __name__ == "__main__":
    raise SystemExit(main())
