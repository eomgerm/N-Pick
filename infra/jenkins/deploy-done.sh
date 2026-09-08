#!/bin/sh
# 배포와 검증이 끝난 뒤에만 호출한다.
# 성공한 배포 커밋을 기록해 다음 빌드의 변경 비교 기준으로 쓰고, 롤백 표시를 지운다.
set -eu
: "${DEPLOY_DIR:?}" "${DEPLOY_SHA:?}"
cd "$DEPLOY_DIR"
echo "$DEPLOY_SHA" > .deploy-sha
rm -f .deploy-rollback
echo "성공 배포 기록 — $DEPLOY_SHA"
