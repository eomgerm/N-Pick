#!/bin/sh
# 직전 커밋 대비 변경 경로를 출력한다. 첫 배포(부모 없음)면 전체를 변경으로 본다.
set -eu
: "${DEPLOY_DIR:?}"
cd "$DEPLOY_DIR"
if git rev-parse --verify --quiet HEAD~1 >/dev/null; then
  git diff --name-only HEAD~1 HEAD
else
  git ls-files
fi
