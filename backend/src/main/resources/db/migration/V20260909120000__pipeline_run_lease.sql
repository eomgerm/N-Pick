-- docs/contracts/job-api.md §6, §11: run 행의 fencing과 회수. 별도 stage/lease 테이블 없음.
ALTER TABLE npick.pipeline_run
    ADD COLUMN lease_id uuid,
    ADD COLUMN lease_stage varchar(48),
    ADD COLUMN lease_worker_id varchar(64),
    ADD COLUMN lease_expires_at timestamptz,
    ADD COLUMN lease_heartbeat_at timestamptz;
CREATE INDEX ix_pipeline_run_lease_expiry ON npick.pipeline_run (lease_expires_at)
    WHERE lease_expires_at IS NOT NULL;
COMMENT ON COLUMN npick.pipeline_run.stage_states_json IS
    '처리 10단계 기록. {"schemaVersion":"npick.stage_states/v1","stages":{"scene_detection":{"status":"pending","attempts":0}}}. 단계 목록은 ai/src/npick_worker/stages.py.';
