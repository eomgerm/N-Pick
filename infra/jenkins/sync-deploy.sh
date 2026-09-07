#!/bin/sh
# /deploy 를 origin/dev 최신으로 맞춘다.
# reset --hard 는 tracked 파일만 되돌리므로 gitignore 된 .env·htpasswd 는 남는다.
# 자격증명은 credential.helper 로 넘겨 remote URL 과 디스크에 남기지 않는다.
set -eu
: "${DEPLOY_DIR:?}" "${GIT_USER:?}" "${GIT_PASS:?}"

HELPER='!f() { echo username=$GIT_USER; echo password=$GIT_PASS; }; f'
git -C "$DEPLOY_DIR" -c credential.helper="$HELPER" fetch --quiet origin dev
git -C "$DEPLOY_DIR" reset --hard --quiet FETCH_HEAD
git -C "$DEPLOY_DIR" log --oneline -1
