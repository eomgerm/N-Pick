#!/bin/sh
# /deploy 를 Jenkins 가 체크아웃한 커밋으로 정확히 맞춘다.
#
# FETCH_HEAD 가 아니라 DEPLOY_SHA 를 체크아웃하는 이유: IMAGE_TAG 는 Jenkins 워크스페이스의
# HEAD 에서 정해지는데, 여기서 브랜치를 다시 fetch 하면 그 사이 갱신된 새 커밋이 올 수 있다.
# 그러면 새 코드를 이전 커밋 태그로 빌드해 태그·소스·GitLab 빌드 상태의 대상이 어긋난다.
# disableConcurrentBuilds() 는 원격 브랜치 갱신을 막지 못한다.
#
# checkout --detach 를 쓰는 이유: reset --hard 는 체크아웃된 브랜치의 포인터를 옮긴다.
# 배포 디렉터리에서 그러면 EC2 의 로컬 브랜치가 엉뚱한 커밋으로 밀린다(2026-09-07 실측).
#
# gitignore 된 .env·htpasswd 는 untracked 라 checkout 이 지우지 않는다. clean 은 쓰지 않는다.
# 자격증명은 credential.helper 로 넘겨 remote URL 과 디스크에 남기지 않는다.
set -eu
: "${DEPLOY_DIR:?}" "${DEPLOY_REF:?}" "${DEPLOY_SHA:?}" "${GIT_USER:?}" "${GIT_PASS:?}"

HELPER='!f() { echo username=$GIT_USER; echo password=$GIT_PASS; }; f'
git -C "$DEPLOY_DIR" -c credential.helper="$HELPER" fetch --quiet origin "$DEPLOY_REF"

if ! git -C "$DEPLOY_DIR" cat-file -e "$DEPLOY_SHA^{commit}" 2>/dev/null; then
  echo "커밋 $DEPLOY_SHA 를 배포 디렉터리에서 찾을 수 없다" >&2
  exit 1
fi

git -C "$DEPLOY_DIR" checkout --detach --quiet "$DEPLOY_SHA"
git -C "$DEPLOY_DIR" log --oneline -1
