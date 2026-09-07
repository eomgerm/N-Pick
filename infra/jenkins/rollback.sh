#!/bin/sh
# 이미지만 되돌린다. Flyway 마이그레이션은 자동 롤백되지 않는다.
# 스키마 변경이 포함된 배포가 실패했다면 이 스크립트로 복구되지 않으므로 사람이 판단한다.
set -eu
: "${DEPLOY_DIR:?}"
cd "$DEPLOY_DIR"

prev=$(cat .deploy-previous 2>/dev/null || true)
if [ -z "$prev" ]; then
  echo "롤백 대상 태그가 없다. 수동 확인이 필요하다" >&2
  exit 1
fi

echo "이전 이미지 $prev 로 롤백"
IMAGE_TAG="$prev" docker compose up -d
echo "$prev" > .deploy-current
