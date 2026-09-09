"""샘플 클립의 keyframe 선정 결과를 눈으로 확인하는 도구.

    uv run --directory ai python -m npick_worker.frame_extraction.report \
        samples/KNI_02205.mp4 --out samples/out/KNI_02205-frames

`scene_detection` 을 먼저 돌려 구간을 얻고 그 구간으로 keyframe 을 뽑는다. 티켓의 완료
조건인 "샘플 영상에 대해 scene detection → frame/keyframe 추출 실제 동작 확인" 을 이
경로로 확인한다.

운영 경로가 아니다. 잡 수신·업로드는 `jobs/` 가 하고 여기서는 로컬 디렉터리에 쓴다
(`scene_detection/report.py` 와 같은 성격의 개발자 도구).
"""

import argparse
import json
import sys
import time
from dataclasses import asdict
from pathlib import Path

from npick_worker.frame_extraction import (
    FrameExtractionResult,
    SceneSpan,
    extract_keyframes,
    load_config,
)
from npick_worker.scene_detection import detect_scenes


def _format_ms(value: int) -> str:
    minutes, remainder = divmod(value, 60_000)
    seconds, millis = divmod(remainder, 1000)
    return f"{minutes:02d}:{seconds:02d}.{millis:03d}"


def render_table(result: FrameExtractionResult) -> str:
    """scene 마다 한 줄. 대표 이미지에 `*` 를 붙인다."""
    header = f"{'scene':>5}  {'n':>2}  {'대표':>12}  {'전체 keyframe':<48}  {'KiB':>6}"
    rows: list[str] = []
    for scene in result.scenes:
        stamps = "  ".join(
            f"{'*' if keyframe is scene.representative else ' '}{_format_ms(keyframe.timestamp_ms)}"
            + ("(blank)" if keyframe.blank else "")
            for keyframe in scene.keyframes
        )
        total_kib = sum(keyframe.byte_size for keyframe in scene.keyframes) // 1024
        rows.append(
            f"{scene.scene_index:>5}  {len(scene.keyframes):>2}  "
            f"{_format_ms(scene.representative.timestamp_ms):>12}  {stamps:<48}  {total_kib:>6}"
        )
    summary = (
        f"scene {len(result.scenes)}개 | keyframe {result.keyframe_count}장 | "
        f"blank {result.blank_count}장 | {result.image_width}x{result.image_height} | "
        f"{result.frame_rate:g}fps | {result.config_version} | "
        f"{result.engine} {result.engine_version}"
    )
    return "\n".join([summary, header, "-" * len(header), *rows])


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="샘플 클립의 keyframe 선정 결과를 출력한다")
    parser.add_argument("video", type=Path, help="입력 영상 경로")
    parser.add_argument("--out", type=Path, required=True, help="keyframes.json·JPEG 저장 디렉터리")
    parser.add_argument("--config", type=Path, default=None, help="임계값 실험용 toml 경로")
    parser.add_argument(
        "--scenes",
        type=Path,
        default=None,
        help="scene_detection.report 가 만든 scenes.json. 없으면 여기서 분할한다",
    )
    return parser.parse_args()


def _load_scenes(args: argparse.Namespace) -> tuple[SceneSpan, ...]:
    """구간을 얻는다. 저장된 scenes.json 이 있으면 다시 분할하지 않는다.

    분할을 다시 돌리면 같은 결과가 나오지만(모듈 재현성 계약) 긴 클립에서 시간이
    두 배로 든다. 실제 파이프라인에서도 이 단계는 상류 산출물을 **받아서** 쓴다.
    """
    if args.scenes is not None:
        payload = json.loads(args.scenes.read_text(encoding="utf-8"))
        return tuple(
            SceneSpan(
                scene_index=scene["scene_index"],
                start_time_ms=scene["start_time_ms"],
                end_time_ms=scene["end_time_ms"],
            )
            for scene in payload["scenes"]
        )
    detection = detect_scenes(args.video)
    print(f"scene {len(detection.scenes)}개 ({detection.config_version})", file=sys.stderr)
    return tuple(
        SceneSpan(
            scene_index=scene.scene_index,
            start_time_ms=scene.start_time_ms,
            end_time_ms=scene.end_time_ms,
        )
        for scene in detection.scenes
    )


def main() -> None:
    args = _parse_args()
    config = load_config(args.config) if args.config is not None else None

    # 경과 시간을 재는 이유는 디코드를 두 번 하는 판단이 FRD §8.2 목표("짧은 영상 분석
    # 30초")에 걸리는지가 이 숫자 하나에 달려 있기 때문이다(docs/frame-extraction.md §10).
    # 문서에 적을 값이 손목시계가 아니라 이 출력이어야 다음 사람이 다시 잴 수 있다.
    started = time.monotonic()
    scenes = _load_scenes(args)
    prepared = time.monotonic()

    args.out.mkdir(parents=True, exist_ok=True)
    result = extract_keyframes(args.video, scenes, args.out, config)
    finished = time.monotonic()

    print(render_table(result))
    stage = "scene 분할" if args.scenes is None else "구간 읽기"
    print()
    print(
        f"경과 시간: {stage} {prepared - started:.1f}s + keyframe 추출 "
        f"{finished - prepared:.1f}s = 총 {finished - started:.1f}s"
    )

    payload = args.out / "keyframes.json"
    # sort_keys + 고정 separators: 두 번 돌려 파일을 그대로 비교하면 멱등성이 보인다.
    payload.write_text(
        json.dumps(asdict(result), sort_keys=True, separators=(",", ":"), ensure_ascii=False),
        encoding="utf-8",
    )
    total_kib = (
        sum(keyframe.byte_size for scene in result.scenes for keyframe in scene.keyframes) // 1024
    )
    print(f"\n{payload}\nJPEG {result.keyframe_count}장 / {total_kib} KiB → {args.out}")


if __name__ == "__main__":
    main()
