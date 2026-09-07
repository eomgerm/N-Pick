#!/bin/sh
# 변경된 서비스만 빌드한다. 코드 빌드는 각 Dockerfile 의 build 스테이지에서 일어난다.
set -eu
: "${DEPLOY_DIR:?}" "${IMAGE_TAG:?}" "${BUILD_TARGETS:?}"
cd "$DEPLOY_DIR"
echo "빌드 대상: $BUILD_TARGETS (태그 $IMAGE_TAG)"
# shellcheck disable=SC2086
IMAGE_TAG="$IMAGE_TAG" docker compose build $BUILD_TARGETS
