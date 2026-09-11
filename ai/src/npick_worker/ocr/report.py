"""추출한 keyframe 에서 읽은 화면 글자를 눈으로 확인하는 도구.

    uv run --directory ai python -m npick_worker.ocr.report \
        samples/out/KNI_02205-frames --out samples/out/KNI_02205-ocr

입력은 `frame_extraction.report` 가 만든 디렉터리다(`s0000/kf-000004133.jpg` 구조와
`keyframes.json`). 티켓의 완료 조건 — "샘플 뉴스 영상의 추출 keyframe 에서 OCR 실제
동작 확인" 과 "원문·confidence·위치·대응 keyframe 확인" — 을 이 경로로 확인한다.

운영 경로가 아니다. 잡 수신과 상류 산출물 전달은 `jobs/` 가 하고 여기서는 로컬
디렉터리를 읽는다(`frame_extraction/report.py` 와 같은 성격의 개발자 도구).
"""

import argparse
import json
import re
import sys
import time
from pathlib import Path
from typing import Final

from npick_worker.ocr import KeyframeRef, OcrResult, get_default_config, read_keyframes

#: `frame_extraction` 의 `FILE_NAME_TEMPLATE` 이 만든 이름을 되읽는다.
_FILE_NAME: Final[re.Pattern[str]] = re.compile(
    r"s(?P<scene>\d{4})[/\\]kf-(?P<timestamp>\d{9})\.jpg"
)


def discover_keyframes(frames_dir: Path) -> tuple[tuple[KeyframeRef, ...], dict[str, Path]]:
    """디렉터리에서 keyframe 목록을 만든다.

    운영 경로에서는 이 목록이 BE 의 `inputs.upstream.frameExtraction` 으로 온다.
    여기서는 파일 이름이 곧 `(sceneIndex, timestampMs)` 라 그것을 되읽는다 —
    `storage_key` 자리에는 디렉터리 기준 상대 경로를 쓴다.
    """
    refs: list[KeyframeRef] = []
    paths: dict[str, Path] = {}
    for jpg in sorted(frames_dir.glob("s*/kf-*.jpg")):
        relative = jpg.relative_to(frames_dir).as_posix()
        match = _FILE_NAME.fullmatch(relative)
        if match is None:
            continue
        ref = KeyframeRef(
            scene_index=int(match.group("scene")),
            timestamp_ms=int(match.group("timestamp")),
            storage_key=relative,
        )
        refs.append(ref)
        paths[relative] = jpg
    return tuple(refs), paths


def render_table(result: OcrResult) -> str:
    """keyframe 마다 한 줄. 미달 관측에 `!` 를 붙인다."""
    header = f"{'scene':>5} {'timestamp':>10} {'n':>2}  {'읽은 글자':<60}"
    rows: list[str] = []
    for keyframe in result.keyframes:
        if not keyframe.observations:
            continue
        texts = " | ".join(
            f"{'!' if obs.unverified else ' '}{obs.raw_text}({obs.confidence:.2f})"
            for obs in keyframe.observations
        )
        rows.append(
            f"{keyframe.keyframe.scene_index:>5} "
            f"{keyframe.keyframe.timestamp_ms:>10} "
            f"{len(keyframe.observations):>2}  {texts}"
        )
    summary = (
        f"keyframe {len(result.keyframes)}장 | 관측 {result.observation_count}건 | "
        f"unverified {result.unverified_count}건 | 문구 {result.text_group_count}종 | "
        f"임계값 {result.min_confidence} | {result.config_version} | "
        f"{result.engine} {result.engine_version}"
    )
    return "\n".join([summary, header, "-" * len(header), *rows])


def to_json(result: OcrResult) -> dict[str, object]:
    """`ocr_observation` 행으로 바로 옮길 수 있는 모양.

    잡 API 봉투가 아니다 — 그 변환은 `jobs/models.py` 가 한다. 여기서는 사람이
    읽고 대조할 수 있는 형태로만 덤프한다.
    """
    return {
        "configVersion": result.config_version,
        "engine": result.engine,
        "engineVersion": result.engine_version,
        "tokenizer": result.tokenizer,
        "minConfidence": result.min_confidence,
        "keyframes": [
            {
                "sceneIndex": keyframe.keyframe.scene_index,
                "timestampMs": keyframe.keyframe.timestamp_ms,
                "storageKey": keyframe.keyframe.storage_key,
                "observations": [
                    {
                        "rawText": obs.raw_text,
                        "tokens": obs.tokens_text,
                        "confidence": obs.confidence,
                        "unverified": obs.unverified,
                        "textKey": obs.text_key,
                        "boundingBox": obs.box.to_json(),
                    }
                    for obs in keyframe.observations
                ],
            }
            for keyframe in result.keyframes
        ],
    }


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frames_dir", type=Path, help="frame_extraction.report 의 출력 디렉터리")
    parser.add_argument("--out", type=Path, default=None, help="ocr.json 을 쓸 디렉터리")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    keyframes, paths = discover_keyframes(args.frames_dir)
    if not keyframes:
        print(f"keyframe 을 찾지 못했다: {args.frames_dir}", file=sys.stderr)
        return 1

    config = get_default_config()
    started = time.monotonic()
    result = read_keyframes(keyframes, paths, config=config)
    elapsed = time.monotonic() - started

    print(render_table(result))
    print(
        f"\n경과 {elapsed:.1f}초 "
        f"(keyframe {len(keyframes)}장, 장당 {elapsed / len(keyframes) * 1000:.0f}ms)"
    )

    if args.out is not None:
        args.out.mkdir(parents=True, exist_ok=True)
        target = args.out / "ocr.json"
        target.write_text(
            json.dumps(to_json(result), ensure_ascii=False, indent=1), encoding="utf-8"
        )
        print(f"{target} 에 썼다")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
