#!/bin/sh
# MR 게이트 FE 유닛 테스트. 배포 이미지와 같은 node:24-alpine 컨테이너에서 npm ci 후
# 유닛 테스트(node --test)만 돌린다. Playwright e2e 는 브라우저 다운로드가 무거워 이
# 게이트에서 제외한다(필요하면 별도 잡으로 분리).
#
# npm 캐시는 named volume 으로 재사용한다.
set -eu
cd "$(dirname "$0")/../.."
docker run --rm \
  -v "$PWD/frontend:/app" \
  -v npick-ci-npm:/root/.npm \
  -w /app \
  node:24-alpine \
  sh -c "npm ci && npm test"
