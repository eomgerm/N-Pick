"""표본 클립의 인식 결과를 눈으로 확인하고 실측 표를 만드는 도구.

**모델을 반드시 고른다.** 코드에 기본 모델이 없으므로(FRD §11, 실측 후 확정) `--model` 을
주거나 `NPICK_AI_ASR_MODEL` 을 설정해야 한다. 가중치 실행에는 `gpu` 그룹이 필요하다
(`uv sync --group gpu`). 둘 중 하나가 빠지면 이 도구는 트레이스백 대신 그 사실을 찍고
1 로 끝난다.

    uv run --directory ai python -m npick_worker.asr.report \
        samples/KNI_02205.mp4 --model large-v3-turbo --out samples/out/KNI_02205-asr

    # VAD on/off·임계값 비교. 동봉 설정을 고쳐 가며 재지 않는다 —
    # 어느 값으로 잰 표인지 나중에 알 수 없게 된다.
    uv run --directory ai python -m npick_worker.asr.report \
        samples/silence.mp4 --model large-v3-turbo \
        --config samples/asr.novad.toml --out samples/out/silence-novad

`docs/asr.md` §5 의 표가 이 출력에서 나온다. **환각과 누락을 함께 본다** — 무음 표본에서
나온 문장 수(있으면 환각)와 라벨 대비 놓친 발화(있으면 누락)는 서로 반대 방향으로
움직이므로 한쪽만 보고 임계값을 정하면 반드시 다른 쪽이 나빠진다.

운영 경로가 아니다. 잡 수신과 상류 산출물 전달은 `jobs/` 가 하고 여기서는 로컬 파일을
읽는다(`ocr/report.py` 와 같은 성격의 개발자 도구).
"""

import argparse
import json
import sys
import time
from pathlib import Path

from npick_worker.asr.config import get_default_config, load_config
from npick_worker.asr.models import AsrResult
from npick_worker.asr.recognizer import transcribe_media


def render_table(result: AsrResult) -> str:
    """구간마다 한 줄. 시간은 원본 영상 기준이다."""
    header = f"{'#':>3} {'start':>9} {'end':>9} {'conf':>5} {'nosp':>5}  대사"
    rows = [
        f"{segment.index:>3} "
        f"{_clock(segment.start_ms):>9} "
        f"{_clock(segment.end_ms):>9} "
        f"{segment.confidence:>5.2f} "
        f"{segment.no_speech_prob:>5.2f}  {segment.text}"
        for segment in result.segments
    ]
    # VAD 가 남긴 길이를 함께 찍는다. 이 값이 크고 구간이 0 개인 실행이 무음이 아니라
    # "말은 있었는데 전사가 비었다" 이고, 표에서 누락으로 세야 하는 자리다.
    vad_speech = (
        "판정없음" if result.vad_speech_ms is None else f"{result.vad_speech_ms / 1000:.1f}초"
    )
    summary = (
        f"구간 {len(result.segments)}개 (엔진 {result.raw_segment_count}개, "
        f"버림 blank {result.dropped_blank}·degenerate {result.dropped_degenerate}) | "
        f"전사 {result.speech_ms / 1000:.1f}초 / VAD 발화 {vad_speech} | "
        f"VAD {'on' if result.vad_enabled else 'off'} | "
        f"발화미감지 {'예' if result.no_speech_detected else '아니오'} | "
        f"{result.config_version} | {result.engine} {result.engine_version} "
        f"| {result.model_version}"
    )
    return "\n".join([summary, header, "-" * len(header), *rows])


def to_json(result: AsrResult) -> dict[str, object]:
    """실측 표에 옮길 수 있는 모양.

    잡 API 봉투가 아니다 — 그 변환은 `jobs/models.py` 가 한다. 여기서는 사람이 라벨과
    대조할 수 있는 형태로만 덤프한다.
    """
    return {
        "configVersion": result.config_version,
        "engine": result.engine,
        "engineVersion": result.engine_version,
        "modelVersion": result.model_version,
        "vadEnabled": result.vad_enabled,
        "vadSpeechMs": result.vad_speech_ms,
        "noSpeechDetected": result.no_speech_detected,
        "rawSegmentCount": result.raw_segment_count,
        "droppedBlank": result.dropped_blank,
        "droppedDegenerate": result.dropped_degenerate,
        "speechMs": result.speech_ms,
        "segments": [
            {
                "index": segment.index,
                "startMs": segment.start_ms,
                "endMs": segment.end_ms,
                "text": segment.text,
                "confidence": segment.confidence,
                "noSpeechProb": segment.no_speech_prob,
            }
            for segment in result.segments
        ],
    }


def _clock(ms: int) -> str:
    """`mm:ss.mmm`. 라벨과 눈으로 맞추려면 초가 아니라 시계여야 한다."""
    minutes, remainder = divmod(ms, 60_000)
    return f"{minutes:02d}:{remainder / 1000:06.3f}"


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("media", type=Path, help="표본 클립(mp4 등)")
    parser.add_argument(
        "--model",
        default=None,
        help="가중치 식별자(예: large-v3-turbo). 주지 않으면 NPICK_AI_ASR_MODEL 을 쓴다",
    )
    parser.add_argument("--config", type=Path, default=None, help="비교용 설정 toml")
    parser.add_argument("--out", type=Path, default=None, help="asr.json 을 쓸 디렉터리")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    if not args.media.is_file():
        print(f"표본을 찾지 못했다: {args.media}", file=sys.stderr)
        return 1

    # 여기서만 backend 를 직접 부른다. 운영 경로에서 어댑터를 고르는 일은
    # `jobs/registry.py` 의 몫이고, 이 도구는 그 배선 없이 엔진만 쓴다.
    from npick_worker.asr.engine import AsrModelUnavailableError
    from npick_worker.asr.faster_whisper_backend import (
        AsrRuntimeMissingError,
        build_engine,
        shared_engine,
    )

    try:
        engine = build_engine(args.model) if args.model else shared_engine()
    except (AsrModelUnavailableError, AsrRuntimeMissingError) as exc:
        # 모델을 고르지 않았거나 라이브러리가 없는 것은 이 도구의 버그가 아니다.
        # 트레이스백 대신 무엇을 해야 하는지 알려 준다 — 모델명은 실측 후 확정 대상이라
        # 코드가 고르지 않는다(FRD §11). `vlm_metadata/report.py` 와 같은 처리다.
        print(f"{exc}", file=sys.stderr)
        print(
            "  --model 로 후보를 주거나 NPICK_AI_ASR_MODEL 을 설정한다. "
            "가중치 실행에는 gpu 그룹이 필요하다: uv sync --group gpu",
            file=sys.stderr,
        )
        return 1

    config = load_config(args.config) if args.config is not None else get_default_config()
    # **가중치 로딩까지 포함해 잰다.** 엔진은 첫 인식에서 가중치를 올리므로(생성은 싸다)
    # 이 값이 곧 "이 후보를 한 번 돌리려면 얼마가 걸리는가" 다.
    started = time.monotonic()
    result = transcribe_media(args.media, engine, config)
    elapsed = time.monotonic() - started

    print(render_table(result))
    print(f"\n경과 {elapsed:.1f}초")

    if args.out is not None:
        args.out.mkdir(parents=True, exist_ok=True)
        target = args.out / "asr.json"
        target.write_text(
            json.dumps(to_json(result), ensure_ascii=False, indent=1), encoding="utf-8"
        )
        print(f"{target} 에 썼다")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
