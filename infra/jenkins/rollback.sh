#!/bin/sh
# 이번 실행이 배포를 시작한 경우에만 되돌린다.
#
# .deploy-rollback 은 deploy.sh 가 up -d 직전에 만들고 deploy-done.sh 가 지운다.
# 파일이 없으면 Sync·Detect·Build 단계에서 실패한 것이므로 운영 중인 버전을 건드리지 않는다.
#
# 이미지만 되돌린다. Flyway 마이그레이션은 자동 롤백되지 않으므로,
# 스키마 변경이 포함된 배포가 실패했다면 이 스크립트로 복구되지 않는다. 사람이 판단한다.
set -eu
: "${DEPLOY_DIR:?}"
cd "$DEPLOY_DIR"

if [ ! -f .deploy-rollback ]; then
  echo "이번 실행은 배포를 시작하지 않았다. 현재 서비스를 그대로 둔다"
  exit 0
fi

if ! grep -q '^BACKEND_TAG=' .deploy-rollback; then
  echo "롤백 파일 형식이 올바르지 않다. 수동 확인이 필요하다:" >&2
  cat .deploy-rollback >&2
  exit 1
fi

set -a
. ./.deploy-rollback
set +a
echo "롤백 — backend $BACKEND_TAG · frontend $FRONTEND_TAG · ai-worker $AI_TAG"

docker compose up -d
rm -f .deploy-rollback
