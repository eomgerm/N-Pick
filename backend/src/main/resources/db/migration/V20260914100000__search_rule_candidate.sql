-- patch_parse 후보 생성(S15P21A501-81)이 쓸 두 칸을 search_rule 에 더한다.
-- baseline 을 직접 고치지 않는 이유: 이미 적용된 migration 을 수정하면 checksum 이 달라져
-- validate-on-migrate 가 켜진 기존 DB 의 기동이 실패한다 (backend/README.md, V20260910160000 과 같은 이유).

-- 교체 대상. 검증 조합(활성 규칙 − R1 + R2) 계산이 JSON 파싱 없이 성립하도록 명시 컬럼으로 둔다.
-- 후보만 교체를 지정하므로 patch_parse 가 아닌 행에는 채우지 못하게 막는다.
ALTER TABLE npick.search_rule
    ADD COLUMN replaces_rule_id bigint,
    ADD COLUMN request_key varchar(64);

ALTER TABLE npick.search_rule
    ADD CONSTRAINT fk_search_rule_replaces
        FOREIGN KEY (replaces_rule_id) REFERENCES npick.search_rule (search_rule_id) ON DELETE RESTRICT;

ALTER TABLE npick.search_rule
    ADD CONSTRAINT ck_search_rule_replaces_shape
        CHECK (replaces_rule_id IS NULL OR action = 'patch_parse');

-- 멱등. 같은 신고에서 같은 요청키의 재시도가 후보를 중복 생성하지 않게 한다.
-- request_key 가 NULL 인 행(후보 경로가 아닌 규칙)은 이 제약이 묶지 않는다 (PostgreSQL 은 NULL 을 서로 다르게 본다).
-- 변경안 수정(=새 요청키)은 새 후보 row 로 정상 생성된다.
ALTER TABLE npick.search_rule
    ADD CONSTRAINT uq_search_rule_feedback_request UNIQUE (source_feedback_id, request_key);

COMMENT ON COLUMN npick.search_rule.replaces_rule_id IS
    '이 후보가 교체할 활성 patch_parse 규칙. 검증 조합(활성 규칙 − R1 + R2) 계산에 쓴다. 교체가 없으면 NULL. patch_parse 행에만 채운다 (S15P21A501-81).';
COMMENT ON COLUMN npick.search_rule.request_key IS
    '후보 생성 멱등 키. 같은 (source_feedback_id, request_key) 재요청은 후보를 중복 생성하지 않는다. 후보 경로가 아닌 규칙은 NULL (S15P21A501-81).';
