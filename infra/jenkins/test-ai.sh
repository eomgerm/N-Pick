#!/bin/sh
# ⚠️ 아직 Jenkinsfile MR 게이트에 배선하지 않았다. torch/transformers 를 무조건 import 하는
#    테스트 12개(test_asr·test_entity_extraction)가 uv 기본 그룹(torch 없음)에서 실패하는데
#    gpu 마커가 없어 제외할 수 없다. AI 팀이 그 12개에 @pytest.mark.gpu 를 달면
#    아래 pytest 를 `-m "not smoke and not gpu"` 로 바꾸고 Jenkinsfile Test 병렬에 추가한다.
#    (그전까지 이 스크립트는 준비만 된 상태다.)
#
# MR 게이트 AI 테스트. 배포 이미지와 같은 python 3.12 + uv 0.12.9 조합을 uv 공식 이미지로
# 한 번에 얻어, dev 그룹(pytest 포함)까지 동기화한 뒤 pytest 를 돌린다. gpu 그룹은 opt-in
# 이라 받지 않는다(torch 미설치). 테스트는 미디어를 mock 하므로 ffmpeg 등 시스템 의존성이
# 필요 없다.
#
# uv 캐시는 named volume 으로 재사용한다.
set -eu
cd "$(dirname "$0")/../.."
docker run --rm \
  -v "$PWD/ai:/app" \
  -v npick-ci-uv:/root/.cache/uv \
  -w /app \
  ghcr.io/astral-sh/uv:0.12.9-python3.12-trixie-slim \
  sh -c "uv sync --frozen && uv run pytest"
