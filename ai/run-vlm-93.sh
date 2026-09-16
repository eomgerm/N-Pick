#!/usr/bin/env bash
# S15P21A501-93. 선정 모델의 실제 출력에서 '없음' 표기를 실측한다.
#
# GPU 서버의 `ai/` 디렉터리에서 인자 없이 실행한다:
#
#     bash run-vlm-93.sh
#
# `run-vlm-smoke.sh` 와 달리 **revision 을 고정한다**. 09-13 비교(§9.6)와 같은 가중치라야
# 그때와 달라진 부분을 계약 변화(v1 -> v2) 때문이라고 말할 수 있다. `main` 으로 두면
# Qwen 이 그 사이 가중치를 옮겼을 때 무엇이 달라진 것인지 구분할 수 없다.
set -u -o pipefail

# §2·§9.6 의 선정 모델. 4B 는 메모리 제약 시 대체 모델이지 기본값이 아니다.
model="${1:-Qwen/Qwen3.5-9B}"
revision="${2:-c202236235762e1c871ad0ccb60c8ee5ba337b9a}"
budget="${3:-20}"

frames="samples/out/KNI_02205-frames"
if [ ! -d "$frames" ]; then
  echo "프레임이 없습니다: $frames (ai/ 디렉터리에서 실행했는지 확인하세요)" >&2
  exit 2
fi

export CUDA_VISIBLE_DEVICES="${CUDA_VISIBLE_DEVICES:-0}"
export TOKENIZERS_PARALLELISM=false

out="samples/out/vlm-bench/$(date -u +%Y%m%dT%H%M%SZ)-${model//\//--}"
mkdir -p "$(dirname "$out")"

echo "모델   : $model"
echo "revision: $revision"
echo "예산   : ${budget} GiB (PyTorch allocator 상한이지 GPU 전체 격리가 아님)"
echo "출력   : $out"
nvidia-smi --query-gpu=index,name,memory.total,memory.used --format=csv || true

uv sync --locked --group gpu --group cu128 || exit 1

uv run --locked --group gpu --group cu128 python -u -m npick_worker.vlm_metadata.benchmark \
  "$frames" --model "$model" --revision "$revision" --out "$out" \
  --memory-budget-gib "$budget" --dtype bfloat16 --thinking off --limit 10 \
  2>&1 | tee "$out.log"
code=${PIPESTATUS[0]}
printf '%s\n' "$code" > "$out.exit-code.txt"

echo
echo "=== summary.json ==="
# 로딩 이전에 실패하면 summary.json 이 없다. 그때는 run.json 과 로그를 본다.
cat "$out/summary.json" 2>/dev/null || echo "(없음 — $out/run.json 과 $out.log 를 확인하세요)"
echo
echo "결과 폴더를 통째로 회수하세요: $out"
echo "  tar czf ${out##*/}.tgz -C $(dirname "$out") ${out##*/} ${out##*/}.log"
exit "$code"
