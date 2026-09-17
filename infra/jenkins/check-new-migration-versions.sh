#!/bin/sh
# MR 에 새로 추가된 Flyway migration 이 대상 브랜치의 최대 버전보다 큰지 검사한다.
# 운영 DB 는 조회하지 않는다. 실제 적용 이력 검사는 dev 배포 전 check-migration-versions.sh 가 맡는다.
set -eu

target_ref=${1:?"대상 브랜치 ref가 필요하다 (예: origin/dev)"}
migration_dir=backend/src/main/resources/db/migration

if ! git rev-parse --verify "${target_ref}^{commit}" >/dev/null 2>&1; then
  echo "대상 브랜치 ref를 찾을 수 없다: $target_ref" >&2
  exit 1
fi

max_target=$(
  git ls-tree -r --name-only "$target_ref" -- "$migration_dir" |
    sed -n 's#^.*/V\([0-9]\{14\}\)__.*\.sql$#\1#p' |
    sort |
    tail -n 1
)

new_migrations=$(mktemp)
trap 'rm -f "$new_migrations"' EXIT HUP INT TERM

# rename 도 삭제+추가로 보아 새 파일명 검사를 우회하지 못하게 한다.
git diff --no-renames --diff-filter=A --name-only "$target_ref"...HEAD -- "$migration_dir" > "$new_migrations"

if [ ! -s "$new_migrations" ]; then
  echo "MR에 새로 추가된 Flyway migration이 없다"
  exit 0
fi

violations=0
added=0
while IFS= read -r migration; do
  [ -n "$migration" ] || continue
  filename=${migration##*/}
  added=$((added + 1))

  case "$filename" in
    V*__*.sql) ;;
    *)
      echo "Flyway migration 파일명이 올바르지 않다: $filename" >&2
      violations=$((violations + 1))
      continue
      ;;
  esac

  version=${filename#V}
  version=${version%%__*}
  case "$version" in
    ''|*[!0-9]*)
      echo "Flyway migration 버전은 숫자여야 한다: $filename" >&2
      violations=$((violations + 1))
      continue
      ;;
  esac

  if [ "${#version}" -ne 14 ]; then
    echo "Flyway migration 버전은 YYYYMMDDHHMMSS 14자리여야 한다: $filename" >&2
    violations=$((violations + 1))
    continue
  fi

  if [ -n "$max_target" ] && [ "$version" -le "$max_target" ]; then
    echo "migration 버전 역전: $filename ($version) <= $target_ref 최대 버전 $max_target" >&2
    violations=$((violations + 1))
  else
    echo "신규 migration 확인: $filename ($version > ${max_target:-없음})"
  fi
done < "$new_migrations"

if [ "$violations" -gt 0 ]; then
  echo "Flyway migration 버전 검사를 통과하지 못했다. 새 버전은 ${max_target:-대상 브랜치의 모든 버전} 보다 커야 한다" >&2
  exit 1
fi

echo "MR Flyway migration 버전 검사 통과 — $target_ref 최대 ${max_target:-없음} · 신규 $added 개"
