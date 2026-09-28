#!/bin/bash
# 검색 확장 설치 + MLflow 백엔드 스토어 격리 (S15P21A501-151)
#
# 이 스크립트는 데이터 디렉터리가 비어 있는 "첫 기동"에만 실행된다.
# 이미 초기화된 볼륨에 적용하려면 infra/compose/README.md 의 수동 절차를 따른다.
set -euo pipefail

: "${MLFLOW_DB_PASSWORD:?MLFLOW_DB_PASSWORD 를 .env 에 설정해야 한다}"

psql -v ON_ERROR_STOP=1 \
	--username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
	-v mlflow_pw="$MLFLOW_DB_PASSWORD" -v service_db="$POSTGRES_DB" <<-'EOSQL'
	-- BM25(pg_search)와 dense(pgvector)가 같은 인스턴스에 있어야 검색 설계가 성립한다.
	-- pg_search 0.25 부터 pgvector 의 vector 타입에 의존하므로 vector 를 먼저 만든다.
	--
	-- WITH SCHEMA public 이 필수다. 이 시점에는 01-create-schema.sql 이 만든 npick 스키마가
	-- 있어서 search_path 의 "$user" 가 처음으로 해석되고, 스키마를 생략하면 확장이 npick 에
	-- 들어간다. pg_search 는 public.vector 타입을 참조하므로 그러면 설치가 실패한다.
	CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
	CREATE EXTENSION IF NOT EXISTS pg_search;

	-- 실험 트래픽이 서비스 커넥션을 잠식하지 못하게 role 레벨에서 상한을 건다.
	-- MLflow 의 풀 크기 설정(MLFLOW_SQLALCHEMYSTORE_POOL_SIZE)은 엔진 재생성 버그로
	-- 실효가 없을 수 있다: https://github.com/mlflow/mlflow/issues/19379
	CREATE ROLE mlflow LOGIN PASSWORD :'mlflow_pw' CONNECTION LIMIT 20;
	CREATE DATABASE mlflow OWNER mlflow;

	-- PUBLIC 이 기본 CONNECT 를 갖고 있어 mlflow 만 REVOKE 해서는 접속이 막히지 않는다.
	-- 각 DB 의 소유자는 이 REVOKE 후에도 접속할 수 있다.
	REVOKE CONNECT ON DATABASE :"service_db" FROM PUBLIC;
	REVOKE CONNECT ON DATABASE mlflow FROM PUBLIC;
EOSQL
