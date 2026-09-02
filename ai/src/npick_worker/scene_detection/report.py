"""샘플 클립 분할 결과를 눈으로 확인하는 도구.

    uv run --directory ai python -m npick_worker.scene_detection.report \
        samples/01-standard-report.mp4 --out samples/out/01

운영 경로가 아니다. HTTP 표면을 늘리지 않고 분할 품질을 확인하기 위한
개발자 도구다(ai/AGENTS.md — 작업 수신 방식은 S15P21A501-70).
"""

import argparse
import json
from dataclasses import asdict
from pathlib import Path

import av

from npick_worker.scene_detection import SceneDetectionResult, detect_scenes, load_config


def _format_ms(value: int) -> str:
    minutes, remainder = divmod(value, 60_000)
    seconds, millis = divmod(remainder, 1000)
    return f"{minutes:02d}:{seconds:02d}.{millis:03d}"


def render_table(result: SceneDetectionResult) -> str:
    header = f"{'idx':>4}  {'start':>12}  {'end':>12}  {'dur':>12}"
    rows = [
        f"{scene.scene_index:>4}  {_format_ms(scene.start_time_ms):>12}  "
        f"{_format_ms(scene.end_time_ms):>12}  {_format_ms(scene.duration_ms):>12}"
        for scene in result.scenes
    ]
    summary = (
        f"scene {len(result.scenes)}개 | {_format_ms(result.duration_ms)} | "
        f"{result.frame_rate:g}fps | {result.detector} | {result.config_version}"
    )
    return "\n".join([summary, header, "-" * len(header), *rows])


def save_boundary_frames(video_path: Path, result: SceneDetectionResult, out_dir: Path) -> int:
    """scene 마다 첫 프레임을 PNG 로 남긴다.

    PyAV 에 번들된 ffmpeg 의 png 인코더를 쓴다 — Pillow·OpenCV 를 새로 끌어오지
    않기 위해서다. 한 번의 순차 디코드로 전부 뽑는다(탐색은 키프레임 정렬 때문에
    경계를 놓칠 수 있다).
    """
    wanted = {scene.start_time_ms: scene.scene_index for scene in result.scenes}
    saved = 0
    with av.open(str(video_path)) as container:
        stream = container.streams.video[0]
        for frame_number, frame in enumerate(container.decode(stream)):
            frame_ms = round(frame_number * 1000 / result.frame_rate)
            index = wanted.pop(frame_ms, None)
            if index is None:
                continue
            target = out_dir / f"scene-{index:04d}-{frame_ms:09d}ms.png"
            _encode_png(frame, target)
            saved += 1
            if not wanted:
                break
    return saved


def _encode_png(frame: av.VideoFrame, target: Path) -> None:
    rgb = frame.reformat(format="rgb24")
    with av.open(str(target), "w", format="image2") as out:
        stream = out.add_stream("png", rate=1)
        stream.width = rgb.width
        stream.height = rgb.height
        stream.pix_fmt = "rgb24"
        for packet in stream.encode(rgb):
            out.mux(packet)
        for packet in stream.encode():
            out.mux(packet)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="샘플 클립 scene 분할 결과를 출력한다")
    parser.add_argument("video", type=Path, help="입력 영상 경로")
    parser.add_argument("--out", type=Path, default=None, help="scenes.json·PNG 저장 디렉터리")
    parser.add_argument("--config", type=Path, default=None, help="임계값 실험용 toml 경로")
    parser.add_argument("--no-frames", action="store_true", help="PNG 를 만들지 않는다")
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    config = load_config(args.config) if args.config is not None else None
    result = detect_scenes(args.video, config)

    print(render_table(result))

    if args.out is None:
        return
    args.out.mkdir(parents=True, exist_ok=True)
    payload = args.out / "scenes.json"
    # sort_keys + 고정 separators: 두 번 돌려 파일을 그대로 비교하면 멱등성이 보인다.
    payload.write_text(
        json.dumps(asdict(result), sort_keys=True, separators=(",", ":"), ensure_ascii=False),
        encoding="utf-8",
    )
    print(f"\n{payload}")
    if not args.no_frames:
        print(f"경계 프레임 {save_boundary_frames(args.video, result, args.out)}장 → {args.out}")


if __name__ == "__main__":
    main()
