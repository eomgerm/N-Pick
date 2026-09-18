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

# **배포 전에 없던 서비스는 되돌릴 대상이 아니라 제거 대상이다.** 과거 태그의 이미지가
# 존재하지 않으므로 up -d 에 포함하면 롤백 자체가 "이미지 없음" 으로 죽고, 실패한 배포가
# 만든 서비스만 남는다 (S15P21A501-187 리뷰 지적).
# NEW_SERVICES 가 없는 옛 형식의 파일도 그대로 동작한다.
targets=$(docker compose config --services)
if [ -n "${NEW_SERVICES:-}" ]; then
  echo "배포 전에 없던 서비스를 제거한다:$NEW_SERVICES"
  # shellcheck disable=SC2086
  docker compose rm -sf $NEW_SERVICES || true
  for service in $NEW_SERVICES; do
    targets=$(printf '%s
' "$targets" | grep -vx "$service" || true)
  done
fi

# 대상을 명시한다. 인자 없는 up -d 는 방금 제거한 서비스를 다시 만든다.
# shellcheck disable=SC2086
docker compose up -d $targets
rm -f .deploy-rollback
