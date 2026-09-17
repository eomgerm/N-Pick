#!/bin/sh
# Jenkins MR 검증용 임시 병합을 만든다.
# 대상 브랜치를 먼저 체크아웃한 뒤 호출해야 GitLab의 merge 결과와 같은 부모 순서를 유지한다.
set -eu

source_branch=${1:?"MR 소스 브랜치가 필요하다"}
target_branch=${2:?"MR 대상 브랜치가 필요하다"}
remote=origin

for branch in "$source_branch" "$target_branch"; do
  if ! git check-ref-format --branch "$branch" >/dev/null 2>&1; then
    echo "올바르지 않은 Git 브랜치명이다: $branch" >&2
    exit 1
  fi
done

source_ref="refs/remotes/$remote/$source_branch"
target_ref="refs/remotes/$remote/$target_branch"

for ref in "$source_ref" "$target_ref"; do
  if ! git rev-parse --verify "${ref}^{commit}" >/dev/null 2>&1; then
    echo "원격 브랜치 ref를 찾을 수 없다: $ref" >&2
    exit 1
  fi
done

head_sha=$(git rev-parse HEAD)
target_sha=$(git rev-parse "$target_ref")
if [ "$head_sha" != "$target_sha" ]; then
  echo "MR 임시 병합은 대상 브랜치에서 시작해야 한다: HEAD $head_sha != $target_ref $target_sha" >&2
  exit 1
fi

# 비 fast-forward 병합은 임시 merge commit을 만들므로 committer identity가 필요하다.
# 워크스페이스 저장소에만 설정해 Jenkins 전역 설정과 다른 잡에는 영향을 주지 않는다.
git config --local user.name "Jenkins CI"
git config --local user.email "jenkins@npick.local"

echo "MR 임시 병합: $source_ref -> $target_ref"
git merge --no-edit "$source_ref"
