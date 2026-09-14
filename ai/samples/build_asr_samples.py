"""ASR 표본 A1~A3 과 그 라벨을 만든다. A4 는 원본 클립을 그대로 쓴다.

    uv run --directory ai python samples/build_asr_samples.py

`docs/asr.md` §5 의 표가 이 표본에서 나온다. **결과물(wav)은 커밋하지 않는다** — A2·A3
은 원본 방송분의 오디오 조각을 담고 있어 영상과 같은 취급이다. 대신 이 스크립트와
라벨을 커밋한다. 난수 씨앗과 잘라 오는 위치가 고정돼 있으므로 누가 언제 돌려도 **바이트
단위로 같은 파일**이 나오고, 그래야 §5 의 수치를 다시 잴 수 있다.

## 왜 표본을 합성하는가

**사람이 듣지 않고는 정답을 적을 수 없기 때문이다.** 뉴스 방송분의 원문 전사는 사람의
일이고 그것 없이 CER·누락률을 적으면 그 수치는 근거가 없다(FRD §11). 그래서 여기서는
**구성으로 정답이 정해지는 표본만** 만든다.

- A1 무음·잡음 — 발화를 넣지 않았으므로 정답이 "구간 0 개" 다. 여기서 나오는 문장은
  전부 환각이고, 그 판정에 사람의 귀가 필요 없다.
- A2 감쇠 — 같은 조각의 음량만 낮췄으므로 정답 구간이 다섯 파일에서 모두 같다.
  "몇 dB 부터 놓치는가" 가 곧 누락이다.
- A3 경계 — 알려진 위치에 심었으므로 정답 시각이 구성으로 정해진다. VAD 가 무음을 잘라
  낸 좌표를 원본 타임라인으로 되돌리는지(`docs/asr.md` §4)를 이것으로 확인한다.

라벨의 **시각은 정답이지만 원문은 아니다.** 조각의 원문은 사람이 확인한 것이 아니라
인식 결과를 옮겨 둔 것이라 `textVerified: false` 로 적는다(형식은 `README.md`).

WAV 로 쓴다. AAC 재인코딩은 조용한 구간을 실제로 바꾸므로 A2 의 감쇠 축이 오염된다.
"""

import json
import sys
import wave
from pathlib import Path

import numpy as np

SR = 16_000
SAMPLES = Path(__file__).resolve().parent
SOURCE = SAMPLES / "KNI_02205.mp4"

#: 재현 가능한 잡음. 바꾸면 §5 의 A1 행을 다시 재야 한다.
SEED = 20260914

#: 원본에서 잘라 오는 구간과 그 인식 결과(사람 확인 전).
CHUNK_START_MS = 4440
CHUNK_END_MS = 7560
CHUNK_TEXT = "지금 이곳에서는 막바지 준비 작업이 한창입니다."

#: A3 에서 조각을 심는 위치. 앞뒤로 넉넉한 무음이 있어야 VAD 가 경계를 판정한다.
A3_PAD_MS = 12_000
#: A2 는 짧게 둔다. 여기서 보는 것은 경계가 아니라 감지 여부다.
A2_PAD_MS = 3_000

#: A2 의 감쇠 단계(dB). 조각 원본이 -23.3 dBFS 이므로 -32 dB 는 약 -55 dBFS 다.
ATTENUATIONS_DB = (0, -12, -20, -26, -32)

#: A2 에 까는 바닥 잡음(dBFS). **이것이 없으면 감쇠 표본은 쓸모가 없다** — 신호 전체를
#: 같은 비율로 줄이면 SNR 이 그대로라 "작은 목소리" 가 아니라 "볼륨을 낮춘 또렷한 목소리"
#: 가 된다. 바닥을 고정하고 발화만 줄여야 감쇠가 곧 SNR 저하가 된다. 조각이 -23.3 dBFS
#: 이므로 -45 는 감쇠 0/-12/-20/-26/-32 dB 에서 SNR 약 22/10/2/-4/-10 dB 다.
A2_NOISE_FLOOR_DBFS = -45.0

#: A1 잡음의 크기(dBFS). 둘을 두는 이유는 -38 을 VAD 가 쉽게 걸러 냈기 때문이다 —
#: 한 단계만 재면 "VAD 가 잡음을 막는다" 가 어디까지 참인지 알 수 없다.
NOISE_DBFS = (-38.0, -25.0)


def _write_wav(path: Path, audio: np.ndarray) -> None:
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SR)
        handle.writeframes(pcm.tobytes())
    print(f"  {path.name}  {len(audio) / SR:.2f}s")


def _write_label(name: str, note: str, segments: list[dict[str, object]]) -> None:
    """`asr-ground-truth.<클립>.json`. 빈 라벨도 반드시 쓴다 — A1 은 그것이 요점이다."""
    payload = {"clip": name, "origin": "constructed", "note": note, "segments": segments}
    target = SAMPLES / f"asr-ground-truth.{name}.json"
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def _embed(chunk: np.ndarray, pad_ms: int) -> np.ndarray:
    pad = np.zeros(pad_ms * SR // 1000, dtype=np.float32)
    return np.concatenate([pad, chunk, pad])


def _spoken(start_ms: int, duration_ms: int, audibility: str) -> list[dict[str, object]]:
    return [
        {
            "startMs": start_ms,
            "endMs": start_ms + duration_ms,
            "text": CHUNK_TEXT,
            "audibility": audibility,
            # 시각은 구성으로 정해진 정답이지만 원문은 사람이 확인하지 않았다.
            "textVerified": False,
        }
    ]


def main() -> int:
    if not SOURCE.is_file():
        print(f"원본이 없다: {SOURCE}", file=sys.stderr)
        return 1

    # faster-whisper 의 디코더를 쓴다. 워커가 오디오를 읽는 경로와 같아야 한다.
    from faster_whisper.audio import decode_audio

    rng = np.random.default_rng(SEED)

    print("A1 — 발화가 없다 (정답: 구간 0 개)")
    _write_wav(SAMPLES / "asr-A1-silence.wav", np.zeros(30 * SR, dtype=np.float32))
    _write_label("asr-A1-silence", "디지털 무음 30초. 발화를 넣지 않았다.", [])
    for level in NOISE_DBFS:
        noise = rng.normal(0.0, 1.0, 30 * SR).astype(np.float32)
        noise *= 10 ** (level / 20.0) / float(np.sqrt(np.mean(np.square(noise))))
        name = f"asr-A1-noise{abs(int(level))}"
        _write_wav(SAMPLES / f"{name}.wav", noise)
        _write_label(name, f"백색 잡음 30초, {level:.0f} dBFS. 발화를 넣지 않았다.", [])

    audio = decode_audio(str(SOURCE), sampling_rate=SR)
    chunk = audio[CHUNK_START_MS * SR // 1000 : CHUNK_END_MS * SR // 1000]
    duration_ms = CHUNK_END_MS - CHUNK_START_MS
    level = 20 * np.log10(float(np.sqrt(np.mean(np.square(chunk)))))
    print(
        f"\n조각: {SOURCE.name} {CHUNK_START_MS}~{CHUNK_END_MS}ms, "
        f"{duration_ms}ms, {level:.1f} dBFS"
    )

    print(f"\nA3 — 경계 (정답: 발화가 {A3_PAD_MS}~{A3_PAD_MS + duration_ms}ms)")
    _write_wav(SAMPLES / "asr-A3-boundary.wav", _embed(chunk, A3_PAD_MS))
    _write_label(
        "asr-A3-boundary",
        f"{SOURCE.name} 의 {CHUNK_START_MS}~{CHUNK_END_MS}ms 를 무음 {A3_PAD_MS}ms 뒤에 심었다. "
        "시각이 이만큼 어긋나면 VAD 타임스탬프 되돌림이 깨진 것이다.",
        _spoken(A3_PAD_MS, duration_ms, "clear"),
    )

    a2_end = A2_PAD_MS + duration_ms
    print(f"\nA2 — 감쇠 (정답: 발화가 {A2_PAD_MS}~{a2_end}ms, 다섯 파일 모두 같다)")
    for db in ATTENUATIONS_DB:
        name = f"asr-A2-gain{db:+d}db"
        quiet = _embed(chunk * (10 ** (db / 20.0)), A2_PAD_MS)
        floor = rng.normal(0.0, 1.0, len(quiet)).astype(np.float32)
        floor *= 10 ** (A2_NOISE_FLOOR_DBFS / 20.0) / float(np.sqrt(np.mean(np.square(floor))))
        snr = 20 * np.log10(float(np.sqrt(np.mean(np.square(chunk))))) - A2_NOISE_FLOOR_DBFS + db
        _write_wav(SAMPLES / f"{name}.wav", quiet + floor)
        _write_label(
            name,
            f"A3 과 같은 조각을 {db:+d} dB 감쇠하고 {A2_NOISE_FLOOR_DBFS:.0f} dBFS 바닥 잡음을 "
            f"깔아 무음 {A2_PAD_MS}ms 뒤에 심었다 (SNR 약 {snr:.0f} dB).",
            # 사람이 알아들을 수 있는 하한은 재봐야 안다. 감쇠 표본의 audibility 는
            # 라벨이 아니라 물음이므로 비워 두지 않고 미확인으로 적는다.
            _spoken(A2_PAD_MS, duration_ms, "clear" if db >= -20 else "faint"),
        )
        print(f"    {name}  SNR 약 {snr:.0f} dB")

    print("\nA4 는 원본 KNI_02205.mp4 를 그대로 쓴다. **라벨이 없다** — 뉴스 원문 전사는")
    print("사람의 일이고, 그것 없이는 CER·누락률을 적을 수 없다(docs/asr.md §5).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
