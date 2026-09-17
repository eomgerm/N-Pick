-- 리졸버 호출 전에 원문과 실행자만으로 running 기록을 남긴다.
-- 해석·필터·설정 snapshot은 이후 독립 트랜잭션에서 채워진다.
ALTER TABLE npick.search_execution
    ALTER COLUMN normalized_query DROP NOT NULL,
    ALTER COLUMN explicit_filters_json DROP NOT NULL,
    ALTER COLUMN normalized_filters_json DROP NOT NULL,
    ALTER COLUMN query_fingerprint DROP NOT NULL,
    ALTER COLUMN normalization_version DROP NOT NULL,
    ALTER COLUMN degraded_reasons_json DROP NOT NULL,
    ALTER COLUMN applied_excludes_json DROP NOT NULL,
    ALTER COLUMN search_config_json DROP NOT NULL,
    ALTER COLUMN config_version DROP NOT NULL;
