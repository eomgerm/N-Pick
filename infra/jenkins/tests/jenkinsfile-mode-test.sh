#!/usr/bin/env bash
# Jenkinsfile 이 PIPELINE_MODE 를 env 로 읽지 않는지 검사한다.
#
#   bash infra/jenkins/tests/jenkinsfile-mode-test.sh
#
# PIPELINE_MODE 는 스크립트 전역 변수다(Jenkinsfile 머리 주석). env.PIPELINE_MODE 는 늘
# 비어 있어서 그걸 보는 when 은 항상 거짓이 되고, 스테이지가 조용히 건너뛰어진 채 빌드는
# SUCCESS 가 된다. 2026-09-18~23 MR 테스트 게이트가 이렇게 한 번도 돌지 않았다
# (S15P21A501-306).
set -euo pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT="$HERE/../../.."

# 주석 줄은 뺀다. 머리 주석이 이 함정을 설명하느라 env.PIPELINE_MODE 를 적는다.
hits=$(grep -nE 'env\.PIPELINE_MODE' "$ROOT/Jenkinsfile" "$ROOT/Jenkinsfile.ops" \
  | grep -vE '^[^:]+:[0-9]+:[[:space:]]*//' || true)

if [ -n "$hits" ]; then
  echo "FAIL: env.PIPELINE_MODE 는 늘 비어 있다. PIPELINE_MODE 를 쓴다:"
  echo "$hits"
  exit 1
fi
echo "ok"
