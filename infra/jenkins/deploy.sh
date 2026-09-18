#!/bin/sh
# CI 가 만든 이미지를 그대로 띄운다(--build 없음).
#
# 서비스별로 태그를 따로 정한다. 바뀐 서비스는 새 커밋 해시, 안 바뀐 서비스는 지금 도는
# 컨테이너의 태그를 그대로 넘긴다. image 문자열이 그대로면 compose 가 재생성하지 않으므로
# 안 바뀐 앱은 끊기지 않는다.
#
# up -d 직전에 .deploy-rollback 을 남긴다. 이 파일의 존재가 "이번 실행이 배포를 시작했다"는
# 표시이며, rollback.sh 는 이 파일이 있을 때만 동작한다. 빌드 단계에서 실패하면 파일이 없어
# 정상 운영 중인 버전을 건드리지 않는다.
set -eu
: "${DEPLOY_DIR:?}" "${IMAGE_TAG:?}"
: "${BUILD_BACKEND:?}" "${BUILD_FRONTEND:?}" "${BUILD_AI:?}"
cd "$DEPLOY_DIR"

current_tag() {
  ref=$(docker inspect --format '{{.Config.Image}}' "npick-$1" 2>/dev/null || true)
  case "$ref" in
    npick/*:*) printf '%s' "${ref##*:}" ;;
    *)         printf '' ;;
  esac
}

resolve() {
  if [ "$2" = "yes" ]; then
    printf '%s' "$IMAGE_TAG"
    return
  fi
  tag=$(current_tag "$1")
  if [ -z "$tag" ]; then
    echo "$1: 실행 중 컨테이너가 없어 태그를 승계할 수 없다. 전체 빌드가 필요하다" >&2
    exit 1
  fi
  printf '%s' "$tag"
}

# **배포 전에 없던 서비스를 적어 둔다.** 롤백은 과거 태그로 up -d 하는데, 이번에 처음
# 생기는 서비스는 과거 태그의 이미지가 존재하지 않는다. 그대로 두면 롤백 자체가
# "이미지 없음" 으로 죽고 새 서비스는 살아남는다 (S15P21A501-187 리뷰 지적).
# 컨테이너 이름은 전부 npick-<서비스> 규칙이라 그것으로 판별한다.
new_services=""
for service in $(docker compose config --services); do
  docker inspect "npick-$service" >/dev/null 2>&1 || new_services="$new_services $service"
done
[ -z "$new_services" ] || echo "이번 배포에서 처음 생기는 서비스:$new_services"

# 되돌릴 대상은 up -d 이전에 실제로 돌던 태그다.
prev_backend=$(current_tag backend)
prev_frontend=$(current_tag frontend)
prev_ai=$(current_tag ai-worker)

BACKEND_TAG=$(resolve backend   "$BUILD_BACKEND")
FRONTEND_TAG=$(resolve frontend "$BUILD_FRONTEND")
AI_TAG=$(resolve ai-worker      "$BUILD_AI")
export BACKEND_TAG FRONTEND_TAG AI_TAG

echo "배포 태그 — backend $BACKEND_TAG · frontend $FRONTEND_TAG · ai-worker $AI_TAG"

if [ -n "$prev_backend" ] && [ -n "$prev_frontend" ] && [ -n "$prev_ai" ]; then
  {
    echo "BACKEND_TAG=$prev_backend"
    echo "FRONTEND_TAG=$prev_frontend"
    echo "AI_TAG=$prev_ai"
    echo "NEW_SERVICES=\"$new_services\""
  } > .deploy-rollback
  echo "롤백 대상 기록 — backend $prev_backend · frontend $prev_frontend · ai-worker $prev_ai"
else
  rm -f .deploy-rollback
  echo "실행 중 컨테이너가 없어 롤백 대상을 기록하지 않는다(첫 배포)"
fi

docker compose up -d
