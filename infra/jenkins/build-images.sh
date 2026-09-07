#!/bin/sh
# 변경된 서비스만 빌드한다. 코드 빌드는 각 Dockerfile 의 build 스테이지에서 일어난다.
# 세 태그 변수를 모두 커밋 해시로 두지만, 실제로 빌드되는 것은 BUILD_TARGETS 에 있는
# 서비스뿐이므로 나머지에는 영향이 없다.
set -eu
: "${DEPLOY_DIR:?}" "${IMAGE_TAG:?}" "${BUILD_TARGETS:?}"
cd "$DEPLOY_DIR"
echo "빌드 대상: $BUILD_TARGETS (태그 $IMAGE_TAG)"
# shellcheck disable=SC2086
BACKEND_TAG="$IMAGE_TAG" FRONTEND_TAG="$IMAGE_TAG" AI_TAG="$IMAGE_TAG" \
  docker compose build $BUILD_TARGETS
