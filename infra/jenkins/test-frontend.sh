#!/bin/sh
# MR 게이트 FE 유닛 테스트. node:24-alpine 컨테이너에서 npm ci 후 유닛 테스트(node --test)만 돌린다.
# Playwright e2e 는 브라우저 다운로드가 무거워 이 게이트에서 제외한다(필요하면 별도 잡으로).
#
# 경로(이다인 P1): test-backend.sh 와 같은 이유 — Jenkins 컨테이너 안의 $PWD 를 docker.sock 으로
# bind-mount 하면 호스트 데몬이 빈 경로로 해석한다. Jenkins 컨테이너 볼륨을 상속(--volumes-from)해
# $WORKSPACE 를 같은 경로로 노출한다. npm 캐시는 named volume 으로 재사용한다.
set -eu
: "${WORKSPACE:?WORKSPACE 가 설정돼야 한다(Jenkins 워크스페이스 경로)}"
docker run --rm \
  --volumes-from "${JENKINS_CONTAINER:-$(hostname)}" \
  -v npick-ci-npm:/root/.npm \
  -w "$WORKSPACE/frontend" \
  node:24-alpine \
  sh -c "npm ci && npm test"
