#!/bin/sh
# /deploy 를 빌드 중인 커밋으로 맞춘다.
#
# checkout --detach 를 쓰는 이유: reset --hard 는 체크아웃된 브랜치의 포인터를 옮긴다.
# 배포 디렉터리에서 그러면 EC2 의 로컬 브랜치가 엉뚱한 커밋으로 밀린다(2026-09-07 실측).
# detach 는 작업 트리만 바꾸고 어떤 브랜치도 건드리지 않는다.
#
# gitignore 된 .env·htpasswd 는 untracked 라 checkout 이 지우지 않는다. clean 은 쓰지 않는다.
# 자격증명은 credential.helper 로 넘겨 remote URL 과 디스크에 남기지 않는다.
set -eu
: "${DEPLOY_DIR:?}" "${DEPLOY_REF:?}" "${GIT_USER:?}" "${GIT_PASS:?}"

HELPER='!f() { echo username=$GIT_USER; echo password=$GIT_PASS; }; f'
git -C "$DEPLOY_DIR" -c credential.helper="$HELPER" fetch --quiet origin "$DEPLOY_REF"
git -C "$DEPLOY_DIR" checkout --detach --quiet FETCH_HEAD
git -C "$DEPLOY_DIR" log --oneline -1
