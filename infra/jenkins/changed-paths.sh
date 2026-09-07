#!/bin/sh
# 마지막 성공 배포 커밋부터 현재 커밋까지의 변경 경로를 출력한다.
#
# HEAD~1 을 쓰면 안 된다. 이전 배포가 실패했거나 커밋을 건너뛴 경우 아직 배포되지 않은
# 앱 변경이 빠지고, deploy.sh 가 기존 태그를 승계하므로 그 변경이 계속 미배포로 남는다.
# 성공 기록이 없으면 전체를 변경으로 본다.
set -eu
: "${DEPLOY_DIR:?}"
cd "$DEPLOY_DIR"

base=""
if [ -f .deploy-sha ]; then
  base=$(cat .deploy-sha)
  if ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
    echo "기록된 배포 커밋 $base 를 찾을 수 없다. 전체를 변경으로 본다" >&2
    base=""
  fi
fi

if [ -z "$base" ]; then
  git ls-files
else
  git diff --name-only "$base" HEAD
fi
