#!/bin/sh
# 운영 DB 에 적용된 Flyway 최대 버전보다 낮거나 같은 미적용 migration 을 배포 전에 막는다.
# 빈 DB 에서는 비교할 적용 이력이 없으므로 통과한다.
set -eu
: "${DEPLOY_DIR:?}"
cd "$DEPLOY_DIR"

migration_dir=backend/src/main/resources/db/migration
if [ ! -d "$migration_dir" ]; then
  echo "migration 디렉터리를 찾을 수 없다: $migration_dir" >&2
  exit 1
fi

# 운영 이력이 있는 환경에서 DB 조회 실패를 '첫 배포'로 오인하면 역전을 놓친다.
# 성공 배포 기록이 없는 실제 첫 배포만 DB 컨테이너가 없어도 통과시킨다.
if ! docker compose ps --status running --services 2>/dev/null | grep -Fxq postgres; then
  if [ -f .deploy-sha ]; then
    echo "postgres가 실행 중이 아니어서 Flyway 적용 이력을 확인할 수 없다" >&2
    exit 1
  fi
  echo "첫 배포 전이라 Flyway 적용 이력이 없다. migration 버전 검사를 건너뛴다"
  exit 0
fi

history_exists=$(
  docker compose exec -T postgres sh -c \
    "psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Atc \"SELECT to_regclass('npick.flyway_schema_history') IS NOT NULL;\""
)

case "$history_exists" in
  t) ;;
  f)
    echo "Flyway 적용 이력이 없는 빈 DB다. migration 버전 검사를 통과한다"
    exit 0
    ;;
  *)
    echo "Flyway 이력 테이블 존재 여부를 확인하지 못했다: $history_exists" >&2
    exit 1
    ;;
esac

applied_versions=$(
  docker compose exec -T postgres sh -c \
    "psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Atc \"SELECT version FROM npick.flyway_schema_history WHERE success = true AND version ~ '^[0-9]+$' ORDER BY version::numeric;\""
)

max_applied=$(printf '%s\n' "$applied_versions" | tail -n 1)
if [ -z "$max_applied" ]; then
  echo "성공 적용된 버전형 migration 이 없다. migration 버전 검사를 통과한다"
  exit 0
fi

violations=0
pending=0
for migration in "$migration_dir"/V*.sql; do
  [ -f "$migration" ] || continue
  filename=${migration##*/}

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

  if printf '%s\n' "$applied_versions" | grep -Fxq "$version"; then
    continue
  fi

  pending=$((pending + 1))
  if [ "$version" -le "$max_applied" ]; then
    echo "migration 버전 역전: $filename ($version) <= 적용된 최대 버전 $max_applied" >&2
    violations=$((violations + 1))
  else
    echo "미적용 migration 확인: $filename ($version > $max_applied)"
  fi
done

if [ "$violations" -gt 0 ]; then
  echo "Flyway migration 버전 검사를 통과하지 못했다. 새 버전은 $max_applied 보다 커야 한다" >&2
  exit 1
fi

echo "Flyway migration 버전 검사 통과 — 적용 최대 $max_applied · 미적용 $pending 개"
