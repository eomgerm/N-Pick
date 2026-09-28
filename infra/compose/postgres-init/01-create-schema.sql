-- npick 스키마를 만든다. 애플리케이션의 Flyway 는 create-schemas=false 라 스키마를 만들지 않는다.
-- postgres 이미지는 이 디렉터리의 스크립트를 데이터 볼륨이 비어 있을 때 1회만 실행한다.
-- 이미 쓰던 볼륨에는 적용되지 않으므로 그때는 아래를 직접 실행한다.
--   docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
--     -c 'CREATE SCHEMA IF NOT EXISTS npick'
CREATE SCHEMA IF NOT EXISTS npick AUTHORIZATION CURRENT_USER;
