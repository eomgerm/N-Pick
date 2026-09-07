#!/bin/sh
# CI 가 만든 이미지를 그대로 띄운다(--build 없음).
# 안 바뀐 서비스는 이미지가 없으므로 실행 중 컨테이너의 이미지에 새 태그를 붙인다.
set -eu
: "${DEPLOY_DIR:?}" "${IMAGE_TAG:?}"
cd "$DEPLOY_DIR"

for svc in backend frontend ai-worker; do
  if docker image inspect "npick/$svc:$IMAGE_TAG" >/dev/null 2>&1; then
    continue
  fi
  cur=$(docker inspect --format '{{.Image}}' "npick-$svc" 2>/dev/null || true)
  if [ -z "$cur" ]; then
    echo "$svc: 실행 중 컨테이너가 없어 태그를 승계할 수 없다. 전체 빌드가 필요하다" >&2
    exit 1
  fi
  docker tag "$cur" "npick/$svc:$IMAGE_TAG"
  echo "$svc: 기존 이미지에 $IMAGE_TAG 태그 승계"
done

# 실패 시 되돌릴 태그를 배포 직전에 기록한다.
if [ -f .deploy-current ]; then
  cp .deploy-current .deploy-previous
fi
echo "$IMAGE_TAG" > .deploy-current

IMAGE_TAG="$IMAGE_TAG" docker compose up -d
