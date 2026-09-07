#!/bin/sh
# CI 가 만든 이미지를 그대로 띄운다(--build 없음).
#
# 서비스별로 태그를 따로 정한다. 바뀐 서비스는 새 커밋 해시, 안 바뀐 서비스는 지금 도는
# 컨테이너의 태그를 그대로 넘긴다. image 문자열이 그대로면 compose 가 재생성하지 않으므로
# 안 바뀐 앱은 끊기지 않는다.
set -eu
: "${DEPLOY_DIR:?}" "${IMAGE_TAG:?}"
: "${BUILD_BACKEND:?}" "${BUILD_FRONTEND:?}" "${BUILD_AI:?}"
cd "$DEPLOY_DIR"

# 지금 도는 컨테이너의 이미지 태그를 읽는다. 없으면 빈 문자열.
current_tag() {
  ref=$(docker inspect --format '{{.Config.Image}}' "npick-$1" 2>/dev/null || true)
  case "$ref" in
    npick/*:*) printf '%s' "${ref##*:}" ;;
    *)         printf '' ;;
  esac
}

resolve() {
  svc=$1
  if [ "$2" = "yes" ]; then
    printf '%s' "$IMAGE_TAG"
    return
  fi
  tag=$(current_tag "$svc")
  if [ -z "$tag" ]; then
    echo "$svc: 실행 중 컨테이너가 없어 태그를 승계할 수 없다. 전체 빌드가 필요하다" >&2
    exit 1
  fi
  printf '%s' "$tag"
}

BACKEND_TAG=$(resolve backend   "$BUILD_BACKEND")
FRONTEND_TAG=$(resolve frontend "$BUILD_FRONTEND")
AI_TAG=$(resolve ai-worker      "$BUILD_AI")
export BACKEND_TAG FRONTEND_TAG AI_TAG

echo "배포 태그 — backend $BACKEND_TAG · frontend $FRONTEND_TAG · ai-worker $AI_TAG"

# 실패 시 되돌릴 태그를 배포 직전에 기록한다.
if [ -f .deploy-current ]; then
  cp .deploy-current .deploy-previous
fi
{
  echo "BACKEND_TAG=$BACKEND_TAG"
  echo "FRONTEND_TAG=$FRONTEND_TAG"
  echo "AI_TAG=$AI_TAG"
} > .deploy-current

docker compose up -d
