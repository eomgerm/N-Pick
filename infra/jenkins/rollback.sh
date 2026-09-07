#!/bin/sh
# 이미지만 되돌린다. Flyway 마이그레이션은 자동 롤백되지 않으므로,
# 스키마 변경이 포함된 배포가 실패했다면 이 스크립트로 복구되지 않는다. 사람이 판단한다.
set -eu
: "${DEPLOY_DIR:?}"
cd "$DEPLOY_DIR"

if [ ! -f .deploy-previous ]; then
  echo "롤백 대상 태그가 없다. 수동 확인이 필요하다" >&2
  exit 1
fi

# 형식 검사. 예전 판본은 태그 한 줄만 적었으므로 source 하면 그 값을 명령으로 실행한다.
if ! grep -q '^BACKEND_TAG=' .deploy-previous; then
  echo "롤백 파일 형식이 예전 것이다. 수동 확인이 필요하다:" >&2
  cat .deploy-previous >&2
  exit 1
fi

# 서비스별 태그를 그대로 되돌린다.
set -a
. ./.deploy-previous
set +a
echo "롤백 — backend $BACKEND_TAG · frontend $FRONTEND_TAG · ai-worker $AI_TAG"

docker compose up -d
cp .deploy-previous .deploy-current
