#!/bin/sh
# MR 게이트 FE 검사. node:24-alpine 컨테이너에서 npm ci 후 타입 검사(tsc)와 유닛 테스트(node --test)를 돌린다.
# 유닛 테스트는 .mjs 라 tsc 를 거치지 않는다. 타입 검사가 없으면 두 MR 이 각자 초록인데 합치면
# 빌드가 깨지는 경우(S15P21A501-305: 한쪽이 함수 인자를 늘리고 다른 쪽이 옛 인자로 호출)를 못 잡는다.
# Playwright e2e 는 브라우저 다운로드가 무거워 이 게이트에서 제외한다(필요하면 별도 잡으로).
#
# 경로(이다인 P1): test-backend.sh 와 같은 이유 — Jenkins 컨테이너 안의 $PWD 를 docker.sock 으로
# bind-mount 하면 호스트 데몬이 빈 경로로 해석한다. Jenkins 컨테이너 볼륨을 상속(--volumes-from)해
# $WORKSPACE 를 같은 경로로 노출한다. npm 캐시는 named volume 으로 재사용한다.
set -eu
: "${WORKSPACE:?WORKSPACE 가 설정돼야 한다(Jenkins 워크스페이스 경로)}"
# 소유권(이다인 P1 후속, test-backend.sh 와 동일): 게이트 컨테이너가 root 로 돌아 npm 산출물
# (node_modules)을 공유 워크스페이스에 root 소유로 남기면 호스트 개발자가 덮어쓰지 못한다.
# npm 캐시 named volume 은 유지하고, 실행 뒤 node_modules 만 워크스페이스 소유자로 되돌린다.
HOST_UID="$(stat -c %u "$WORKSPACE")"
HOST_GID="$(stat -c %g "$WORKSPACE")"
docker run --rm \
  --label "npick.mr.build=${BUILD_TAG:-manual-$$}" \
  --volumes-from "${JENKINS_CONTAINER:-$(hostname)}" \
  -v npick-ci-npm:/root/.npm \
  -w "$WORKSPACE/frontend" \
  node:24-alpine \
  sh -c "npm ci && npm run typecheck && npm test; rc=\$?; chown -R $HOST_UID:$HOST_GID \"$WORKSPACE/frontend/node_modules\" 2>/dev/null || true; exit \$rc"
