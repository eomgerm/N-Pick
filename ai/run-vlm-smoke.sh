#!/usr/bin/env bash
# Linux GPU 서버용. ai/ 디렉터리에서 bash run-vlm-smoke.sh ... 로 실행한다.
set -u -o pipefail

if [ "$#" -lt 3 ]; then
  echo "사용법: bash run-vlm-smoke.sh FRAMES_DIR OUT_ROOT MEMORY_BUDGET_GIB [MODEL ...]" >&2
  exit 2
fi
frames_dir="$1"
out_root="$2"
memory_budget="$3"
shift 3
if [ "$#" -eq 0 ]; then
  set -- Qwen/Qwen3.5-4B Qwen/Qwen3.5-9B Qwen/Qwen3-VL-8B-Instruct
fi
mkdir -p "$out_root"
export CUDA_VISIBLE_DEVICES="${CUDA_VISIBLE_DEVICES:-0}"
export TOKENIZERS_PARALLELISM=false
status=0
for model in "$@"; do
  # 한 프로세스에 한 모델만 올린다. 실패한 후보의 로그도 남기고 다음 후보로 간다.
  label="${model//\//--}"
  run_dir="$out_root/$(date -u +%Y%m%dT%H%M%SZ)-$label-$$"
  echo "실행: $model -> $run_dir"
  uv run --locked --group gpu python -u -m npick_worker.vlm_metadata.benchmark \
    "$frames_dir" --model "$model" --out "$run_dir" \
    --memory-budget-gib "$memory_budget" --dtype bfloat16 --thinking off --limit 10 \
    2>&1 | tee "$run_dir.log"
  code=${PIPESTATUS[0]}
  printf '%s\n' "$code" > "$run_dir.exit-code.txt"
  if [ "$code" -ne 0 ]; then
    status=1
    echo "후보 실패 (exit=$code): $model; run.json과 로그를 확인하세요." >&2
  fi
done
exit "$status"
