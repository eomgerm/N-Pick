#!/usr/bin/env bash
# S15P21A501-93. **부재가 실제로 생기는 입력**을 넣어 '없음' 표기를 재본다.
#
# GPU 서버의 `ai/` 디렉터리에서 인자 없이 실행한다:
#
#     bash run-vlm-93-blank.sh
#
# 09-16 실측(§6)에서 `normalizedScenes` 가 0 이었는데, 그건 모델이 계약대로 썼다는 뜻이
# 아니라 **부재를 표현할 일이 없었다**는 뜻이다. 열 장면 모두 화면에 읽을 것이 있었다.
# 그래서 여기서는 caption 할 것도 scene_type 도 태그도 없는 장면을 일부러 넣는다.
# 빈 화면은 지어낸 입력이지만 방송에서 실제로 나오는 자리다(암전, 전환 사이).
#
# **이건 품질 측정이 아니다.** 모델이 잘 보는지를 보는 것이 아니라, 정보가 없을 때
# 계약의 `null`·`unknown`·`[]` 로 쓰는지 아니면 `"없음"` 이라고 쓰는지만 본다.
# 후보 비교(§9.6)의 성적으로 쓰지 않는다.
set -u -o pipefail

model="${1:-Qwen/Qwen3.5-9B}"
revision="${2:-c202236235762e1c871ad0ccb60c8ee5ba337b9a}"
budget="${3:-20}"

src="samples/out/KNI_02205-frames"
frames="samples/out/KNI_02205-frames-blank"

if [ ! -d "$src" ]; then
  echo "프레임이 없습니다: $src (ai/ 디렉터리에서 실행했는지 확인하세요)" >&2
  exit 2
fi

# **원본을 건드리지 않는다.** `$src` 는 09-13 후보 비교와 09-16 실측이 함께 쓴 입력이라,
# 거기에 장면을 더하면 그 기록들의 `inputs.json` 과 더는 같은 입력이 아니게 된다.
rm -rf "$frames"
cp -r "$src" "$frames"
rm -f "$frames/keyframes.json"

uv run --locked --group gpu --group cu128 python - "$frames" <<'PY'
"""빈 화면 장면을 뒤에 덧붙인다. 크기·형식은 원본 keyframe 과 같게 맞춘다."""
import sys
from pathlib import Path

from PIL import Image

frames = Path(sys.argv[1])
# 암전·백색·중간 회색. 셋 다 '읽을 것이 없는 화면'이지만 밝기가 달라야 모델이
# "어두워서 안 보인다" 와 "아무것도 없다" 를 구분해 답하는지까지 드러난다.
for offset, (name, color) in enumerate(
    [("black", (0, 0, 0)), ("white", (255, 255, 255)), ("gray", (128, 128, 128))]
):
    scene = frames / f"s{10 + offset:04d}"
    scene.mkdir(parents=True, exist_ok=True)
    for index in range(2):
        stamp = 900_000 + offset * 10_000 + index * 3_000
        Image.new("RGB", (800, 450), color).save(scene / f"kf-{stamp:09d}.jpg", quality=92)
    print(f"{scene.name}: {name} 2장")
PY

export CUDA_VISIBLE_DEVICES="${CUDA_VISIBLE_DEVICES:-0}"
export TOKENIZERS_PARALLELISM=false

out="samples/out/vlm-bench/$(date -u +%Y%m%dT%H%M%SZ)-blank-${model//\//--}"
mkdir -p "$(dirname "$out")"

echo "모델   : $model@$revision"
echo "입력   : $frames (원본 10장면 + 빈 화면 3장면)"
echo "출력   : $out"
nvidia-smi --query-gpu=index,name,memory.total,memory.used --format=csv || true

uv sync --locked --group gpu --group cu128 || exit 1

# `--limit` 은 앞에서부터 자르므로 13 이어야 덧붙인 s0010~s0012 가 들어간다.
uv run --locked --group gpu --group cu128 python -u -m npick_worker.vlm_metadata.benchmark \
  "$frames" --model "$model" --revision "$revision" --out "$out" \
  --memory-budget-gib "$budget" --dtype bfloat16 --thinking off --limit 13 \
  2>&1 | tee "$out.log"
code=${PIPESTATUS[0]}
printf '%s\n' "$code" > "$out.exit-code.txt"

echo
echo "=== summary.json ==="
cat "$out/summary.json" 2>/dev/null || echo "(없음 — $out/run.json 과 $out.log 를 확인하세요)"
echo
echo "=== 빈 화면 3장면이 무엇을 냈는가 ==="
# 정규화가 걸렸는지, 걸렸다면 어느 자리인지. 이 세 줄이 이 실행의 전부다.
uv run --locked --group gpu --group cu128 python - "$out/vlm-metadata.json" <<'PY'
import json
import sys

record = json.loads(open(sys.argv[1], encoding="utf-8").read())
for row in record["scenes"]:
    if row["sceneIndex"] < 10:
        continue
    print(f"--- scene {row['sceneIndex']} | normalizations={row['normalizations']}")
    print(row["rawOutput"])
for row in record["rejected"]:
    if row["sceneIndex"] >= 10:
        print(f"--- scene {row['sceneIndex']} 거부: {row['kind']} {row['reason']}")
        print(row["rawOutput"])
PY
echo
echo "결과 폴더를 통째로 회수하세요: $out"
echo "  tar czf ${out##*/}.tgz -C $(dirname "$out") ${out##*/} ${out##*/}.log"
exit "$code"
