-- #68 technical request journal; the published business baseline remains unchanged.
-- No request text, raw key, rights policy or audit provenance is stored here.
CREATE TABLE npick.registration_request (
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    key_hash varchar(64) NOT NULL CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    content_hash varchar(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    state varchar(16) NOT NULL CHECK (state IN ('processing', 'succeeded', 'failed', 'unknown')),
    clip_id bigint,
    pipeline_run_id bigint,
    result_status varchar(32),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, key_hash),
    CHECK (
        (state = 'succeeded' AND clip_id IS NOT NULL AND clip_id > 0
            AND pipeline_run_id IS NOT NULL AND pipeline_run_id > 0 AND result_status IS NOT NULL)
        OR (state <> 'succeeded' AND clip_id IS NULL AND pipeline_run_id IS NULL AND result_status IS NULL)
    )
);
CREATE INDEX ix_registration_request_unresolved_content
    ON npick.registration_request (content_hash) WHERE state IN ('processing', 'unknown');
COMMENT ON TABLE npick.registration_request IS '등록 재전송 기술 상태. 자동 만료 없음. 불명확 결과는 대조 전 재실행 금지.';
