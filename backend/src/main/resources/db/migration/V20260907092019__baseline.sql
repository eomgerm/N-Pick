-- N-Pick baseline: FRD v3.1 / 최종 ERDCloud (2026-09-04, S15P21A501-153)
-- https://www.erdcloud.com/d/eFoaB2wCwPcCZFNzX
-- 업무 테이블 13개 / 컬럼 134개 / FK 23개 (확장·Flyway 관리 객체 제외).
--
-- timestamp 버전으로 시작하는 baseline이다. 기존 baseline이 적용된 DB의 이관 SQL이 아니다.
-- PK는 앱 생성 bigint TSID. ERD의 타입·NULL 여부·기본값(없음)을 그대로 유지한다.
-- FK는 모두 ON DELETE RESTRICT. 순환 참조 때문에 테이블 생성 후 연결한다.
-- enum 어휘는 서비스가 검증한다. DB는 필요한 값 조합·범위·중복만 방어한다.
-- 같은 클립/신고 소속, 권한, 검증 내용 일치, 불변 이력·동시 확정은 서비스 트랜잭션 책임이다.
-- 별도 이력·잠금·outbox 테이블, 트리거, 임의 JSON 키 검증기를 추가하지 않는다.
-- 교정 후보는 같은 트랜잭션에서 임시 반영→검색→ROLLBACK. 실행 기록은 롤백 밖에 저장한다.
-- 미사용 pin_parse 및 지원 컬럼은 제거. exclude_scene와 복수 patch_parse는 유지한다.
-- 스키마 npick은 infra/compose/postgres-init에서 먼저 생성한다.
-- 확장 설치에는 DB 관리자 권한이 필요하다. 제한된 앱 계정이면 관리자가 사전 설치한다.
SET LOCAL search_path = public;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_search;
SET LOCAL search_path = npick, public;

-- 1. 테이블과 행 단위 제약

CREATE TABLE "member" (
    "member_id" bigint NOT NULL,
    "login_id" varchar(64) NOT NULL,
    "password_hash" varchar(255) NOT NULL,
    "name" varchar(100) NOT NULL,
    "role" varchar(32) NOT NULL,
    "created_at" timestamptz NOT NULL,
    "updated_at" timestamptz NOT NULL,
    CONSTRAINT pk_member PRIMARY KEY ("member_id"),
    CONSTRAINT uq_member_login_id UNIQUE (login_id)
);

CREATE TABLE "clip" (
    "clip_id" bigint NOT NULL,
    "source_type" varchar(32) NOT NULL,
    "storage_key" text NOT NULL,
    "content_hash" varchar(64) NOT NULL,
    "transcript_file_key" text,
    "title" varchar(500),
    "transcript_source" varchar(32) NOT NULL,
    "script_text" text,
    "registered_by_id" bigint NOT NULL,
    "active_pipeline_run_id" bigint,
    "created_at" timestamptz NOT NULL,
    "updated_at" timestamptz NOT NULL,
    "deleted_at" timestamptz,
    CONSTRAINT pk_clip PRIMARY KEY ("clip_id")
);

CREATE TABLE "pipeline_run" (
    "pipeline_run_id" bigint NOT NULL,
    "clip_id" bigint NOT NULL,
    "processing_no" integer NOT NULL,
    "pipeline_version" varchar(128) NOT NULL,
    "status" varchar(32) NOT NULL,
    "stage_states_json" jsonb NOT NULL,
    "error_code" varchar(64),
    "created_at" timestamptz NOT NULL,
    "started_at" timestamptz,
    "finished_at" timestamptz,
    "updated_at" timestamptz NOT NULL,
    CONSTRAINT pk_pipeline_run PRIMARY KEY ("pipeline_run_id"),
    CONSTRAINT uq_pipeline_run_clip_processing UNIQUE (clip_id, processing_no),
    CONSTRAINT ck_pipeline_run_processing_positive CHECK (processing_no > 0)
);

CREATE TABLE "scene" (
    "scene_id" bigint NOT NULL,
    "clip_id" bigint NOT NULL,
    "pipeline_run_id" bigint NOT NULL,
    "start_time_ms" bigint NOT NULL,
    "end_time_ms" bigint NOT NULL,
    "caption" text,
    "caption_tokens" text,
    "shot_type" varchar(32) NOT NULL,
    "transcript_text" text,
    "transcript_tokens" text,
    "transcript_json" jsonb,
    "transcript_source" varchar(32),
    "created_at" timestamptz NOT NULL,
    "updated_at" timestamptz NOT NULL,
    "embedding" vector(1024),
    CONSTRAINT pk_scene PRIMARY KEY ("scene_id"),
    CONSTRAINT ck_scene_interval CHECK (start_time_ms >= 0 AND end_time_ms > start_time_ms)
);

CREATE TABLE "keyframe" (
    "keyframe_id" bigint NOT NULL,
    "scene_id" bigint NOT NULL,
    "timestamp_ms" bigint NOT NULL,
    "storage_key" text NOT NULL,
    CONSTRAINT pk_keyframe PRIMARY KEY ("keyframe_id"),
    CONSTRAINT uq_keyframe_scene_timestamp UNIQUE (scene_id, timestamp_ms),
    CONSTRAINT ck_keyframe_timestamp CHECK (timestamp_ms >= 0)
);

CREATE TABLE "ocr_observation" (
    "ocr_observation_id" bigint NOT NULL,
    "keyframe_id" bigint NOT NULL,
    "raw_text" text NOT NULL,
    "tokens" text NOT NULL,
    "confidence" numeric(5,4) NOT NULL,
    "bounding_box_json" jsonb NOT NULL,
    CONSTRAINT pk_ocr_observation PRIMARY KEY ("ocr_observation_id"),
    CONSTRAINT ck_ocr_confidence CHECK (confidence BETWEEN 0 AND 1)
);

CREATE TABLE "tag" (
    "tag_id" bigint NOT NULL,
    "tag_type" varchar(32) NOT NULL,
    "match_value" varchar(255) NOT NULL,
    "name" text NOT NULL,
    CONSTRAINT pk_tag PRIMARY KEY ("tag_id"),
    CONSTRAINT uq_tag_type_match_value UNIQUE (tag_type, match_value),
    CONSTRAINT ck_tag_date CHECK (tag_type NOT IN ('filmed_date', 'broadcast_date') OR
        (match_value ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND pg_input_is_valid(match_value, 'date')))
);

CREATE TABLE "tagging" (
    "tagging_id" bigint NOT NULL,
    "clip_id" bigint NOT NULL,
    "scene_id" bigint,
    "tag_id" bigint NOT NULL,
    "created_at" timestamptz NOT NULL,
    CONSTRAINT pk_tagging PRIMARY KEY ("tagging_id"),
    CONSTRAINT uq_tagging_scope_tag UNIQUE NULLS NOT DISTINCT (clip_id, scene_id, tag_id)
);

CREATE TABLE "tag_evidence" (
    "evidence_id" bigint NOT NULL,
    "tagging_id" bigint NOT NULL,
    "source" varchar(32) NOT NULL,
    "confidence" numeric(5,4),
    "verification_status" varchar(32) NOT NULL,
    "source_ref_type" varchar(32),
    "source_ref_id" bigint,
    "created_at" timestamptz NOT NULL,
    "source_feedback_id" bigint,
    CONSTRAINT pk_tag_evidence PRIMARY KEY ("evidence_id"),
    CONSTRAINT ck_evidence_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_evidence_source_ref_pair CHECK ((source_ref_type IS NULL) = (source_ref_id IS NULL)),
    CONSTRAINT ck_evidence_review_shape CHECK (
        (source = 'reviewer_feedback' AND source_feedback_id IS NOT NULL AND confidence IS NULL
            AND verification_status IN ('verified', 'rejected', 'withdrawn'))
        OR (source <> 'reviewer_feedback' AND source_feedback_id IS NULL
            AND verification_status IN ('verified', 'unverified')))
);

CREATE TABLE "search_execution" (
    "search_execution_id" bigint NOT NULL,
    "searched_by_id" bigint NOT NULL,
    "query_text" text NOT NULL,
    "normalized_query" text NOT NULL,
    "explicit_filters_json" jsonb NOT NULL,
    "normalized_filters_json" jsonb NOT NULL,
    "query_fingerprint" varchar(64) NOT NULL,
    "normalization_version" varchar(128) NOT NULL,
    "execution_type" varchar(32) NOT NULL,
    "replay_of_feedback_id" bigint,
    "status" varchar(32) NOT NULL,
    "degraded_reasons_json" jsonb NOT NULL,
    "error_code" varchar(64),
    "parse_source" varchar(32),
    "parsed_query_json" jsonb,
    "parser_version" varchar(128),
    "parse_ms" integer,
    "candidates_json" jsonb,
    "filtered_json" jsonb,
    "applied_excludes_json" jsonb NOT NULL,
    "search_config_json" jsonb NOT NULL,
    "config_version" varchar(128) NOT NULL,
    "execution_ms" integer,
    "created_at" timestamptz NOT NULL,
    "updated_at" timestamptz NOT NULL,
    "resolver_output_json" jsonb,
    "applied_rules_json" jsonb,
    "verification_context_json" jsonb,
    CONSTRAINT pk_search_execution PRIMARY KEY ("search_execution_id"),
    CONSTRAINT ck_execution_replay_pair CHECK ((execution_type = 'replay') = (replay_of_feedback_id IS NOT NULL)),
    CONSTRAINT ck_execution_verification_replay CHECK (verification_context_json IS NULL OR
        (execution_type = 'replay' AND replay_of_feedback_id IS NOT NULL)),
    CONSTRAINT ck_execution_duration CHECK ((parse_ms IS NULL OR parse_ms >= 0) AND
        (execution_ms IS NULL OR execution_ms >= 0))
);

CREATE TABLE "search_result" (
    "search_result_id" bigint NOT NULL,
    "search_execution_id" bigint NOT NULL,
    "scene_id" bigint NOT NULL,
    "result_rank" integer NOT NULL,
    "explain_json" jsonb NOT NULL,
    CONSTRAINT pk_search_result PRIMARY KEY ("search_result_id"),
    CONSTRAINT uq_search_result_rank UNIQUE (search_execution_id, result_rank),
    CONSTRAINT uq_search_result_scene UNIQUE (search_execution_id, scene_id),
    CONSTRAINT ck_search_result_rank CHECK (result_rank > 0)
);

CREATE TABLE "feedback" (
    "feedback_id" bigint NOT NULL,
    "search_result_id" bigint NOT NULL,
    "created_by_id" bigint NOT NULL,
    "comment" text,
    "status" varchar(32) NOT NULL,
    "reviewed_by_id" bigint,
    "resolution" varchar(32),
    "created_rule_id" bigint,
    "verified_by_execution_id" bigint,
    "resolution_note" text,
    "created_at" timestamptz NOT NULL,
    "review_started_at" timestamptz,
    "closed_at" timestamptz,
    "updated_at" timestamptz NOT NULL,
    CONSTRAINT pk_feedback PRIMARY KEY ("feedback_id"),
    CONSTRAINT uq_feedback_result_creator UNIQUE (search_result_id, created_by_id)
);

CREATE TABLE "search_rule" (
    "search_rule_id" bigint NOT NULL,
    "query_fingerprint" varchar(64) NOT NULL,
    "normalized_query" text NOT NULL,
    "normalized_filters_json" jsonb NOT NULL,
    "normalization_version" varchar(128) NOT NULL,
    "action" varchar(32) NOT NULL,
    "target_scene_id" bigint,
    "source_feedback_id" bigint NOT NULL,
    "active" boolean NOT NULL,
    "created_at" timestamptz NOT NULL,
    "updated_at" timestamptz NOT NULL,
    "condition_json" jsonb,
    "patch_json" jsonb,
    CONSTRAINT pk_search_rule PRIMARY KEY ("search_rule_id"),
    CONSTRAINT ck_search_rule_action_shape CHECK (
        (action = 'exclude_scene' AND target_scene_id IS NOT NULL
            AND condition_json IS NULL AND patch_json IS NULL)
        OR (action = 'patch_parse' AND target_scene_id IS NULL
            AND condition_json IS NOT NULL AND patch_json IS NOT NULL))
);

-- 2. 관계: ERD의 23개 FK. 순환 포인터는 자식 생성 후 UPDATE한다.
ALTER TABLE "clip" ADD CONSTRAINT fk_clip_registered_by_id
    FOREIGN KEY ("registered_by_id") REFERENCES "member" ("member_id") ON DELETE RESTRICT;
ALTER TABLE "clip" ADD CONSTRAINT fk_clip_active_pipeline_run_id
    FOREIGN KEY ("active_pipeline_run_id") REFERENCES "pipeline_run" ("pipeline_run_id") ON DELETE RESTRICT;
ALTER TABLE "pipeline_run" ADD CONSTRAINT fk_pipeline_run_clip_id
    FOREIGN KEY ("clip_id") REFERENCES "clip" ("clip_id") ON DELETE RESTRICT;
ALTER TABLE "scene" ADD CONSTRAINT fk_scene_clip_id
    FOREIGN KEY ("clip_id") REFERENCES "clip" ("clip_id") ON DELETE RESTRICT;
ALTER TABLE "scene" ADD CONSTRAINT fk_scene_pipeline_run_id
    FOREIGN KEY ("pipeline_run_id") REFERENCES "pipeline_run" ("pipeline_run_id") ON DELETE RESTRICT;
ALTER TABLE "keyframe" ADD CONSTRAINT fk_keyframe_scene_id
    FOREIGN KEY ("scene_id") REFERENCES "scene" ("scene_id") ON DELETE RESTRICT;
ALTER TABLE "ocr_observation" ADD CONSTRAINT fk_ocr_observation_keyframe_id
    FOREIGN KEY ("keyframe_id") REFERENCES "keyframe" ("keyframe_id") ON DELETE RESTRICT;
ALTER TABLE "tagging" ADD CONSTRAINT fk_tagging_clip_id
    FOREIGN KEY ("clip_id") REFERENCES "clip" ("clip_id") ON DELETE RESTRICT;
ALTER TABLE "tagging" ADD CONSTRAINT fk_tagging_scene_id
    FOREIGN KEY ("scene_id") REFERENCES "scene" ("scene_id") ON DELETE RESTRICT;
ALTER TABLE "tagging" ADD CONSTRAINT fk_tagging_tag_id
    FOREIGN KEY ("tag_id") REFERENCES "tag" ("tag_id") ON DELETE RESTRICT;
ALTER TABLE "tag_evidence" ADD CONSTRAINT fk_tag_evidence_tagging_id
    FOREIGN KEY ("tagging_id") REFERENCES "tagging" ("tagging_id") ON DELETE RESTRICT;
ALTER TABLE "tag_evidence" ADD CONSTRAINT fk_tag_evidence_source_feedback_id
    FOREIGN KEY ("source_feedback_id") REFERENCES "feedback" ("feedback_id") ON DELETE RESTRICT;
ALTER TABLE "search_execution" ADD CONSTRAINT fk_search_execution_searched_by_id
    FOREIGN KEY ("searched_by_id") REFERENCES "member" ("member_id") ON DELETE RESTRICT;
ALTER TABLE "search_execution" ADD CONSTRAINT fk_search_execution_replay_of_feedback_id
    FOREIGN KEY ("replay_of_feedback_id") REFERENCES "feedback" ("feedback_id") ON DELETE RESTRICT;
ALTER TABLE "search_result" ADD CONSTRAINT fk_search_result_search_execution_id
    FOREIGN KEY ("search_execution_id") REFERENCES "search_execution" ("search_execution_id") ON DELETE RESTRICT;
ALTER TABLE "search_result" ADD CONSTRAINT fk_search_result_scene_id
    FOREIGN KEY ("scene_id") REFERENCES "scene" ("scene_id") ON DELETE RESTRICT;
ALTER TABLE "feedback" ADD CONSTRAINT fk_feedback_search_result_id
    FOREIGN KEY ("search_result_id") REFERENCES "search_result" ("search_result_id") ON DELETE RESTRICT;
ALTER TABLE "feedback" ADD CONSTRAINT fk_feedback_created_by_id
    FOREIGN KEY ("created_by_id") REFERENCES "member" ("member_id") ON DELETE RESTRICT;
ALTER TABLE "feedback" ADD CONSTRAINT fk_feedback_reviewed_by_id
    FOREIGN KEY ("reviewed_by_id") REFERENCES "member" ("member_id") ON DELETE RESTRICT;
ALTER TABLE "feedback" ADD CONSTRAINT fk_feedback_created_rule_id
    FOREIGN KEY ("created_rule_id") REFERENCES "search_rule" ("search_rule_id") ON DELETE RESTRICT;
ALTER TABLE "feedback" ADD CONSTRAINT fk_feedback_verified_by_execution_id
    FOREIGN KEY ("verified_by_execution_id") REFERENCES "search_execution" ("search_execution_id") ON DELETE RESTRICT;
ALTER TABLE "search_rule" ADD CONSTRAINT fk_search_rule_target_scene_id
    FOREIGN KEY ("target_scene_id") REFERENCES "scene" ("scene_id") ON DELETE RESTRICT;
ALTER TABLE "search_rule" ADD CONSTRAINT fk_search_rule_source_feedback_id
    FOREIGN KEY ("source_feedback_id") REFERENCES "feedback" ("feedback_id") ON DELETE RESTRICT;

-- 3. 중복 방지 및 실제 조회 경로. PK/UNIQUE 선행 컬럼과 중복되는 인덱스는 생략한다.
CREATE UNIQUE INDEX uq_clip_content_hash_alive ON clip (content_hash) WHERE deleted_at IS NULL;
CREATE INDEX ix_clip_registered_by ON clip (registered_by_id);
CREATE INDEX ix_clip_active_run ON clip (active_pipeline_run_id);
CREATE INDEX ix_pipeline_run_queue ON pipeline_run (status, created_at);
CREATE INDEX ix_scene_clip ON scene (clip_id);
CREATE INDEX ix_scene_run ON scene (pipeline_run_id);
CREATE INDEX ix_ocr_keyframe ON ocr_observation (keyframe_id);
CREATE INDEX ix_tagging_scene ON tagging (scene_id);
CREATE INDEX ix_tagging_tag ON tagging (tag_id);
CREATE INDEX ix_evidence_tagging_latest ON tag_evidence (tagging_id, created_at DESC, evidence_id DESC);
CREATE INDEX ix_evidence_feedback ON tag_evidence (source_feedback_id);
CREATE INDEX ix_execution_searcher_created ON search_execution (searched_by_id, created_at DESC);
CREATE INDEX ix_execution_replay_feedback ON search_execution (replay_of_feedback_id);
CREATE INDEX ix_result_scene ON search_result (scene_id);
CREATE INDEX ix_feedback_creator ON feedback (created_by_id);
CREATE INDEX ix_feedback_reviewer ON feedback (reviewed_by_id);
CREATE INDEX ix_feedback_created_rule ON feedback (created_rule_id);
CREATE INDEX ix_feedback_verified_execution ON feedback (verified_by_execution_id);
CREATE INDEX ix_feedback_queue ON feedback (status, created_at);
CREATE INDEX ix_rule_target_scene ON search_rule (target_scene_id);
CREATE INDEX ix_rule_source_feedback ON search_rule (source_feedback_id);
CREATE INDEX ix_rule_active_exact ON search_rule (query_fingerprint, normalization_version)
    WHERE active AND action = 'exclude_scene';
-- 같은 원인 신고의 복수 후보·개정, 독립 패턴 규칙의 복수 활성을 허용한다.
-- 조건 JSON에 대한 범용 GIN 인덱스는 실제 조회 문법이 정해지기 전 추가하지 않는다.

-- 4. 검색 인덱스: Kiwi 토큰을 재분석하지 않는 whitespace tokenizer.
-- pg_search 0.25.6 문법: https://www.paradedb.com/docs/documentation/indexing/create-index
-- 한 테이블의 BM25 필드를 한 인덱스로 모으며 순위 가중치는 실행 설정에서 정한다.
CREATE INDEX ix_scene_bm25 ON scene USING bm25
    (scene_id, clip_id, pipeline_run_id, (caption_tokens::pdb.whitespace), (transcript_tokens::pdb.whitespace))
    WITH (key_field = 'scene_id');
CREATE INDEX ix_ocr_bm25 ON ocr_observation USING bm25
    (ocr_observation_id, keyframe_id, (tokens::pdb.whitespace))
    WITH (key_field = 'ocr_observation_id');
-- scene.embedding vector(1024)는 캡션+대사 벡터 한 개. 1024는 100번에서 확정할 임시값이다.
-- 벡터 검색은 pgvector로 가능하다. ANN 인덱스의 거리 연산자/튜닝은 모델 확정 후 정한다.
-- 거리 함수를 임의로 선택해 HNSW/IVFFlat 인덱스를 고정하지 않는다. RRF 결합은 검색 구현 책임.

-- 5. 테이블·컬럼 주석 (ERD 메모의 13개 테이블 설명 및 전체 컬럼 주석)
COMMENT ON TABLE "member" IS '미리 등록한 편집기자(editor)·검수자(reviewer) 계정. 아이디·비밀번호 로그인과 역할별 권한의 기준.';
COMMENT ON COLUMN "member"."member_id" IS '계정 고유 번호';
COMMENT ON COLUMN "member"."login_id" IS '로그인 아이디. 중복 불가';
COMMENT ON COLUMN "member"."password_hash" IS 'BCrypt 해시. 평문 저장 금지. BCrypt 단독은 60자지만 Spring 기본 방식은 {bcrypt} 접두사가 붙어 68자다. 알고리즘 교체 여지를 두고 255 로 잡았다';
COMMENT ON COLUMN "member"."name" IS '실명';
COMMENT ON COLUMN "member"."role" IS 'editor(편집기자) | reviewer(검수자). 규칙 생성과 영상 등록은 검수자만';
COMMENT ON COLUMN "member"."created_at" IS '만든 시각';
COMMENT ON COLUMN "member"."updated_at" IS '마지막 수정 시각';

COMMENT ON TABLE "clip" IS '방송분·자료 영상 1건과 원본 파일 정보. 방송일·촬영일은 태그로 관리하고 active_pipeline_run_id로 검색에 쓰는 처리를 선택한다.';
COMMENT ON COLUMN "clip"."clip_id" IS '영상 고유 번호';
COMMENT ON COLUMN "clip"."source_type" IS 'broadcast(방송분) | archive(자료 영상). 자료 영상은 방송일 태그가 없다';
COMMENT ON COLUMN "clip"."storage_key" IS '영상 파일 경로';
COMMENT ON COLUMN "clip"."content_hash" IS 'SHA-256 hex. 같은 영상 중복 등록을 막는다(살아 있는 행끼리만)';
COMMENT ON COLUMN "clip"."transcript_file_key" IS '자막 파일 경로. 파싱 후에는 장면 표의 대사 칸이 정본이다';
COMMENT ON COLUMN "clip"."title" IS '영상 제목. 검색 대상';
COMMENT ON COLUMN "clip"."transcript_source" IS 'provided(사람이 만든 자막) | asr(음성인식) | none. 화면에 박힌 글자(슈퍼)는 ocr_observation 이 담당한다';
COMMENT ON COLUMN "clip"."script_text" IS '시각 정보가 없는 대본 전문. AI 가 장면 설명을 만들 때 참고용. 장면에 복제하지 않는다';
COMMENT ON COLUMN "clip"."registered_by_id" IS '등록한 사람. 기획서상 영상 등록은 검수자가 한다';
COMMENT ON COLUMN "clip"."active_pipeline_run_id" IS '지금 검색에 쓰이는 처리. 값이 있으면 검색 가능한 상태다(별도 상태 칸을 두지 않는 이유)';
COMMENT ON COLUMN "clip"."created_at" IS '등록한 시각';
COMMENT ON COLUMN "clip"."updated_at" IS '마지막 수정 시각';
COMMENT ON COLUMN "clip"."deleted_at" IS '논리 삭제 시각. 하드 삭제를 하지 않으므로 유일한 삭제 수단이다';

COMMENT ON TABLE "pipeline_run" IS '영상 1건에 대한 분석 실행 1회. 단계 상태·처리 버전·실패 및 실행 시각을 기록한다.';
COMMENT ON COLUMN "pipeline_run"."pipeline_run_id" IS '처리 고유 번호';
COMMENT ON COLUMN "pipeline_run"."clip_id" IS '어느 영상의 처리인가';
COMMENT ON COLUMN "pipeline_run"."processing_no" IS '이 영상의 몇 번째 처리인지. 장면 표에는 복사하지 않는다 — 재처리하면 새 scene_id 로 새 행이 생겨 옛 결과와 규칙이 자동으로 안 걸린다';
COMMENT ON COLUMN "pipeline_run"."pipeline_version" IS '처리 버전. 이 값 하나가 모델 조합을 결정한다. 버전별 모델 매핑은 설정 파일이 안다';
COMMENT ON COLUMN "pipeline_run"."status" IS 'queued | running | succeeded | failed';
COMMENT ON COLUMN "pipeline_run"."stage_states_json" IS '처리 10단계 각각의 상태. {"scene_detect":{"status":"succeeded","attempts":1},"asr":{"status":"skipped","error_code":"NO_ADAPTER"}}';
COMMENT ON COLUMN "pipeline_run"."error_code" IS '처리 전체 실패 코드. 단계별 원인은 단계 상태 묶음에 있다';
COMMENT ON COLUMN "pipeline_run"."created_at" IS '처리 요청이 들어온 시각. 큐에 쌓였다가 나중에 시작될 수 있어 시작 시각과 나눠 둔다';
COMMENT ON COLUMN "pipeline_run"."started_at" IS '워커가 실제로 집어간 시각';
COMMENT ON COLUMN "pipeline_run"."finished_at" IS '처리가 끝난 시각';
COMMENT ON COLUMN "pipeline_run"."updated_at" IS '마지막 수정 시각';

COMMENT ON TABLE "scene" IS '영상의 시간 구간이자 검색 결과 단위. 설명·대사·검색 토큰과 캡션·대사를 합친 의미 검색 벡터를 보존한다.';
COMMENT ON COLUMN "scene"."scene_id" IS '장면 고유 번호. 검색 결과의 단위다. 재처리하면 새 번호로 새 행이 생긴다';
COMMENT ON COLUMN "scene"."clip_id" IS '어느 영상. 처리를 거쳐도 나오지만 조회가 잦아 직접 들고 있다';
COMMENT ON COLUMN "scene"."pipeline_run_id" IS '어느 처리에서 만들어졌나';
COMMENT ON COLUMN "scene"."start_time_ms" IS '구간 시작(밀리초). 영상을 실제로 자르지 않고 시각만 저장한다';
COMMENT ON COLUMN "scene"."end_time_ms" IS '구간 끝(밀리초). 시작보다 반드시 크다';
COMMENT ON COLUMN "scene"."caption" IS 'AI 가 만든 장면 설명. 검색 대상이다';
COMMENT ON COLUMN "scene"."caption_tokens" IS '장면 설명의 Kiwi 형태소 분석 결과. pg_search 가 색인한다. 칸을 나눠 뒀으므로 대사보다 낮은 가중치를 줄 수 있다';
COMMENT ON COLUMN "scene"."shot_type" IS 'anchor | interview | b_roll | unknown. 분류값 중 유일하게 칸으로 남았다 — 랭킹이 매 검색마다 읽고(b_roll 에 보조 가산점) 평가 지표에도 있다. 계절·날씨·상황유형은 태그로 갔다';
COMMENT ON COLUMN "scene"."transcript_text" IS '이 구간의 대사 원문. 화면에 보여주는 용이다';
COMMENT ON COLUMN "scene"."transcript_tokens" IS '대사의 Kiwi 형태소 분석 결과. pg_search 가 색인한다. 색인과 질의가 같은 Kiwi 설정을 써야 하며 다르면 검색이 0건이 된다';
COMMENT ON COLUMN "scene"."transcript_json" IS '구간별 대사. [{"s":시작ms,"e":끝ms,"t":"대사","overlap_ms":이 장면과 겹친 시간}]. 음성 인식은 영상 전체를 한 번에 돌리고 겹치는 장면 전부에 복사한다';
COMMENT ON COLUMN "scene"."transcript_source" IS '이 장면 대사의 실제 출처. 자막 파일이 앞부분만 커버하면 뒷부분은 음성인식이 채우므로 영상 단위와 다를 수 있다';
COMMENT ON COLUMN "scene"."created_at" IS '만든 시각. 색인이 같은 트랜잭션 안이라 검색 가능해진 시각과 같다';
COMMENT ON COLUMN "scene"."updated_at" IS '마지막 수정 시각';
COMMENT ON COLUMN "scene"."embedding" IS '캡션+대사를 합친 dense 벡터. pgvector 로 색인하고 BM25 순위와 RRF 결합. 차원 1024 는 임시값 — 임베딩 모델 확정 시 재설정해야 하며 바꾸면 전체 재색인';

COMMENT ON TABLE "keyframe" IS '장면에서 추출한 대표 프레임과 영상 내 시각·이미지 경로.';
COMMENT ON COLUMN "keyframe"."keyframe_id" IS '키프레임 고유 번호. 화면 글자가 이 값을 가리킨다';
COMMENT ON COLUMN "keyframe"."scene_id" IS '어느 장면에서 뽑았나';
COMMENT ON COLUMN "keyframe"."timestamp_ms" IS '영상의 몇 초 지점. 같은 장면에서 같은 시각의 프레임은 하나뿐이다';
COMMENT ON COLUMN "keyframe"."storage_key" IS '이미지 파일 경로. 결과 목록의 대표 이미지는 첫 장을 쓴다. 현재 의미 검색은 scene.embedding의 캡션·대사 벡터를 사용한다.';

COMMENT ON TABLE "ocr_observation" IS '키프레임에서 읽은 화면 글자 원문·검색 토큰·신뢰도·위치 근거. 원문은 교정으로 덮어쓰지 않는다.';
COMMENT ON COLUMN "ocr_observation"."ocr_observation_id" IS '화면 글자 관측 고유 번호';
COMMENT ON COLUMN "ocr_observation"."keyframe_id" IS '어느 프레임에서 읽었나';
COMMENT ON COLUMN "ocr_observation"."raw_text" IS '읽은 그대로. 결과 화면에 근거로 보여준다. 절대 덮어쓰지 않는다';
COMMENT ON COLUMN "ocr_observation"."tokens" IS 'Kiwi 형태소 분석 결과. 검색 색인과 태그 매칭에 쓴다';
COMMENT ON COLUMN "ocr_observation"."confidence" IS '0~1. 이 값의 임계값으로 검증 상태를 판정한다. 출처가 하나뿐이라 별도 검증 상태 칸을 두지 않는다';
COMMENT ON COLUMN "ocr_observation"."bounding_box_json" IS '화면 위치. 근거 이미지 위에 박스를 그려 어디서 읽었는지 보여줄 수 있다';

COMMENT ON TABLE "tag" IS '개체·분류·날짜 11종의 공통 태그 사전. 매칭용 값과 표시값을 구분하며 군중 밀도는 포함하지 않는다.';
COMMENT ON COLUMN "tag"."tag_id" IS '태그 고유 번호';
COMMENT ON COLUMN "tag"."tag_type" IS '11가지. 개체 person|organization|location|facility|keyword|event / 분류 season|weather|scene_type / 날짜 filmed_date|broadcast_date. 분류값과 날짜를 태그로 관리한다. FRD v3.1에서 군중 밀도는 범위 밖이며 유형을 추가하지 않는다.';
COMMENT ON COLUMN "tag"."match_value" IS '매칭용 정규화 값. "이태원참사". 날짜 태그는 YYYY-MM-DD 형식을 CHECK 으로 강제한다 — 형식이 깨지면 범위 비교가 무너진다';
COMMENT ON COLUMN "tag"."name" IS '화면 표시값. "이태원 참사". 정규화하면 띄어쓰기가 사라져 되돌릴 수 없어 따로 저장한다';

COMMENT ON TABLE "tagging" IS '클립 전체 또는 특정 장면에 태그를 연결한다. 유효한 장면 검수 판단이 같은 태그의 클립 상속보다 우선한다.';
COMMENT ON COLUMN "tagging"."tagging_id" IS '태그 붙인 기록 고유 번호. 근거 표가 이 값을 가리킨다';
COMMENT ON COLUMN "tagging"."clip_id" IS '어느 영상. 장면 번호가 비어 있을 수 있어 이 칸이 없으면 어느 영상인지 모른다';
COMMENT ON COLUMN "tagging"."scene_id" IS 'NULL이면 클립 전체 태그, 값이 있으면 그 장면 태그. 같은 태그의 유효한 장면 검수 판단이 클립 상속보다 우선한다. 클립 태그의 장면 한정 반려는 같은 tag_id의 장면 tagging으로 기록한다. withdrawn은 해당 범위의 검수 개입을 해제한다.';
COMMENT ON COLUMN "tagging"."tag_id" IS '어느 태그';
COMMENT ON COLUMN "tagging"."created_at" IS '태그를 붙인 시각. 주체(사람/AI)는 tag_evidence.source 가 답한다';

COMMENT ON TABLE "tag_evidence" IS '태깅의 추출 근거와 신고에서 나온 검수 판단. 원본·이전 판단을 보존하고 승인·반려·개입 해제를 추가 기록한다.';
COMMENT ON COLUMN "tag_evidence"."evidence_id" IS '근거 고유 번호';
COMMENT ON COLUMN "tag_evidence"."tagging_id" IS '어느 태그에 대한 근거인가. 한 태그에 근거가 여러 개 붙을 수 있다';
COMMENT ON COLUMN "tag_evidence"."source" IS '어디서 알았나. user_input | original_metadata | cc | ocr | asr | vlm | rule | reviewer_feedback. reviewer_feedback은 신고를 검수한 사람의 태깅 판단이며 source_feedback_id 필수. 일반 추출 근거는 기존 출처·신뢰도 정책을 유지하며 AI의 높은 신뢰도만으로 verified가 되지 않는다.';
COMMENT ON COLUMN "tag_evidence"."confidence" IS '0~1. 일반 추출 근거는 출처와 함께 검증 상태를 정한다. reviewer_feedback의 사람 판단은 NULL이며 임의로 1.0을 넣지 않는다.';
COMMENT ON COLUMN "tag_evidence"."verification_status" IS '일반 추출 근거는 verified | unverified. reviewer_feedback은 verified(승인) | rejected(대상 태깅 반려) | withdrawn(해당 범위의 검수 개입 해제). 최신 판단을 사용한다. withdrawn은 과거 승인을 되살리지 않고 기본 근거·상속으로 복귀한다. 일반 추출 출처에는 withdrawn 금지. 원본·이전 판단을 덮어쓰지 않고 판단을 추가한다.';
COMMENT ON COLUMN "tag_evidence"."source_ref_type" IS '근거가 된 원본 종류. keyframe | ocr_observation | scene';
COMMENT ON COLUMN "tag_evidence"."source_ref_id" IS '근거가 된 원본 번호. 종류 칸과 항상 함께 채우거나 함께 비운다';
COMMENT ON COLUMN "tag_evidence"."created_at" IS '만든 시각. 검수 판단은 직렬화하여 추가 기록하고 최신 판단을 사용한다. 시각 동률은 evidence_id로 구분한다.';
COMMENT ON COLUMN "tag_evidence"."source_feedback_id" IS '검수 판단의 원인 피드백. source가 reviewer_feedback이면 필수. 일반 추출 근거는 NULL. feedback.feedback_id 참조.';

COMMENT ON TABLE "search_execution" IS '일반 검색 또는 검증 replay 1회. 원본·최종 해석, 규칙 적용, 후보·제외, 검증 맥락과 실행 결과를 보존한다.';
COMMENT ON COLUMN "search_execution"."search_execution_id" IS '검색 실행 고유 번호';
COMMENT ON COLUMN "search_execution"."searched_by_id" IS '검색을 요청한 계정. 일반 검색은 편집기자, 후보 검증 replay는 검수자의 실행이다.';
COMMENT ON COLUMN "search_execution"."query_text" IS '사용자가 친 그대로';
COMMENT ON COLUMN "search_execution"."normalized_query" IS '정규화한 질의. 불필요한 말 제거, 별칭 치환, 형태소 분석을 거친 결과다. 규칙 기반 코드가 하며 LLM 이 아니다';
COMMENT ON COLUMN "search_execution"."explicit_filters_json" IS '사용자가 화면에서 직접 건 필터. 원문에 명시된 것이라 AI 추론보다 강하게 적용된다';
COMMENT ON COLUMN "search_execution"."normalized_filters_json" IS '정규화한 필터';
COMMENT ON COLUMN "search_execution"."query_fingerprint" IS '정규화한 질의와 필터의 SHA-256. exclude_scene의 exact 조회 키. patch_parse는 지문 일치가 아니라 resolver 원본 출력과 condition_json으로 매칭한다.';
COMMENT ON COLUMN "search_execution"."normalization_version" IS '정규화 규칙 버전. 이 값이 바로 지문값이 통째로 달라져 기존 규칙이 안 걸리게 된다. 검색 설정 버전과 따로 둔 이유다';
COMMENT ON COLUMN "search_execution"."execution_type" IS 'original(원래 검색) | replay(원 검색의 조건을 재사용한 자동 재검색). replay 중 verification_context_json이 있으면 후보 교정 검증 검색이며 일반 검색·평가 집계와 구분한다.';
COMMENT ON COLUMN "search_execution"."replay_of_feedback_id" IS '어느 피드백 때문에 재검색했는가. 교정 검증 시 필수이며 검증 맥락의 대상·권한 검사에 사용한다. 여러 검증 실행은 이 컬럼으로 역조회한다.';
COMMENT ON COLUMN "search_execution"."status" IS 'running | succeeded | degraded | failed. degraded 는 결과는 나왔는데 일부 기능이 빠졌다는 뜻이다';
COMMENT ON COLUMN "search_execution"."degraded_reasons_json" IS '실제 기능 저하 사유. 규칙 충돌 그룹·출력 계약 비호환·패치 실패 등을 기록한다. 단순 복수 규칙 일치나 정상 조건 불일치는 오류가 아니다. 규칙별 상세 결과는 applied_rules_json에 보존한다.';
COMMENT ON COLUMN "search_execution"."error_code" IS '실패 코드. 해석 실패 종류도 여기로 통합했다';
COMMENT ON COLUMN "search_execution"."parse_source" IS 'resolver(LLM 해석) | fallback(해석 실패 시 단어 검색) | resolver_rule(resolver 후 패턴 규칙을 1개 이상 성공 적용). 단순 일치·건너뜀만으로 resolver_rule로 기록하지 않는다.';
COMMENT ON COLUMN "search_execution"."parsed_query_json" IS '그 실행의 검색 계산에 사용한 최종 해석. 날짜 구간·사건명·인물·조직·장소·시설·의도와 명시/추론 출처를 보존한다. 검증 실행이면 후보가 적용된 해석이다. 기존 최상위 구조를 유지하며 패치 전 원본은 resolver_output_json에 기록한다.';
COMMENT ON COLUMN "search_execution"."parser_version" IS '해석기 버전. 모델·프롬프트·형식 버전 3칸을 하나로 합쳤다';
COMMENT ON COLUMN "search_execution"."parse_ms" IS '해석에 걸린 시간. 앱이 실제로 잴 값이다. 전체 시간 안에 포함되며 더하는 게 아니다';
COMMENT ON COLUMN "search_execution"."candidates_json" IS '거르기 전 후보 목록. 검색은 후보를 뽑고 이상한 걸 걸러내는 2단계로 돌아간다';
COMMENT ON COLUMN "search_execution"."filtered_json" IS '뺀 것들과 뺀 이유. 내가 아는 그 영상이 왜 안 나왔는지에 답하는 유일한 기록이다';
COMMENT ON COLUMN "search_execution"."applied_excludes_json" IS '적용한 장면 제외 규칙 목록. 제외는 여러 개 걸릴 수 있어 목록으로 둔다';
COMMENT ON COLUMN "search_execution"."search_config_json" IS '실제 순위 계산 파라미터. 단어·벡터 가중치, 날짜 허용 범위, 반환 개수 등을 실행 당시 값으로 보존한다. 사람이 평가·튜닝하며 LLM이 만들지 않는다. 후보 태그·승인 맥락은 verification_context_json에 기록한다.';
COMMENT ON COLUMN "search_execution"."config_version" IS '설정 버전. MLflow 기록과 맞추는 열쇠다';
COMMENT ON COLUMN "search_execution"."execution_ms" IS '검색 전체에 걸린 시간. 앱이 실제로 잴 값이다. DB 시각의 차이로 계산하면 요청 도착과 응답 전송 구간이 빠져 부정확하다';
COMMENT ON COLUMN "search_execution"."created_at" IS '검색한 시각. 목록 정렬에 쓴다';
COMMENT ON COLUMN "search_execution"."updated_at" IS '마지막 수정 시각';
COMMENT ON COLUMN "search_execution"."resolver_output_json" IS '규칙 적용 전 resolver 구조화 출력. 유효 출력 획득 실패 등 원본 해석이 없으면 NULL. 최종 해석은 parsed_query_json에 따로 보존한다.';
COMMENT ON COLUMN "search_execution"."applied_rules_json" IS '패턴 규칙 ID·본문 스냅샷·순서·적용/건너뜀/실패 및 사유. 기록 완료 시 빈 내역은 []. 후보 검증 내역은 롤백 대상 밖에 보존하며 임시 후보 ID만으로 본문을 대체하지 않는다.';
COMMENT ON COLUMN "search_execution"."verification_context_json" IS '교정 검증 검색에서만 저장하는 서버 생성 맥락. 후보 태그 변경·범위·기준 데이터, 후보/교체 대상 규칙과 검증 규칙 집합을 보존한다. 후보를 임시 반영한 검색 트랜잭션은 ROLLBACK하고 이 맥락과 실행 기록은 그 밖에 저장한다. 확정 시 변경안·현재 상태를 대조한다. 일반 검색은 NULL.';

COMMENT ON TABLE "search_result" IS '검색 실행의 최종 결과 장면과 순위·설명. 신고가 당시 결과를 참조한다.';
COMMENT ON COLUMN "search_result"."search_result_id" IS '검색 결과 고유 번호. 신고가 이 값을 가리킨다';
COMMENT ON COLUMN "search_result"."search_execution_id" IS '어느 검색의 결과인가';
COMMENT ON COLUMN "search_result"."scene_id" IS '어느 장면. 거르기가 끝난 것만 들어온다. 재처리하면 새 scene_id 로 새 행이 생기므로 옛 결과가 새 장면을 가리키는 일이 없다';
COMMENT ON COLUMN "search_result"."result_rank" IS '몇 등인가. 순서가 결과의 일부고 평가 지표(nDCG, MRR)의 재료다';
COMMENT ON COLUMN "search_result"."explain_json" IS '왜 이 장면이 여기 있는가. {"score":점수 내역, "match":무엇이 걸렸나, "guard":걸러내기 판정}. 결과 카드를 그릴 때 항상 같이 읽어서 한 덩어리로 묶었다';

COMMENT ON TABLE "feedback" IS '특정 검색 결과의 신고와 검수·최종 조치. 확정 전에 확인한 검증 실행과 승인 규칙을 연결한다.';
COMMENT ON COLUMN "feedback"."feedback_id" IS '신고 고유 번호. 교정 규칙과 재검색이 이 값을 가리킨다';
COMMENT ON COLUMN "feedback"."search_result_id" IS '어느 결과에 대한 신고인가. NOT NULL 유지. 이 결과를 통해 원 검색어·필터·해석·후보와 신고 장면·클립을 조회한다.';
COMMENT ON COLUMN "feedback"."created_by_id" IS '신고한 편집기자. 같은 결과에 같은 사람이 두 번 신고할 수 없다';
COMMENT ON COLUMN "feedback"."comment" IS '편집기자가 남긴 말. 선택이라 없어도 신고할 수 있다(원클릭 신고)';
COMMENT ON COLUMN "feedback"."status" IS 'open(접수) | reviewing(검수 중) | closed(검색 반영 확인 후 종료). 후보 자동 재검색·검수자 확인 후 확정한다. 검증·확정·검색 반영 확인이 실패하거나 미완료이면 reviewing을 유지한다. 외부 색인 대기 단계는 두지 않는다. 종료 사유는 resolution에 기록한다.';
COMMENT ON COLUMN "feedback"."reviewed_by_id" IS '검수한 사람';
COMMENT ON COLUMN "feedback"."resolution" IS 'exclude_scene(장면 제외) | no_action(문제없음) | deferred(나중에) | tag_correction(태그 교정) | patch_parse(해석 패턴 교정). 태그와 해석을 함께 교정하면 patch_parse로 기록하고 태그 교정은 연결된 tag_evidence로 조회한다.';
COMMENT ON COLUMN "feedback"."created_rule_id" IS '이 피드백에서 최종 승인한 교정 규칙. 태그만 교정하면 NULL. 해당 규칙의 source_feedback_id는 현재 피드백과 일치해야 한다. 여러 규칙의 검색 적용은 search_execution.applied_rules_json에 기록한다.';
COMMENT ON COLUMN "feedback"."verified_by_execution_id" IS '확정 전에 검수자가 확인한 대표 교정 검증 검색. 같은 피드백의 replay이며 verification_context_json과 확정 변경안이 일치해야 한다. 실행 성공만으로 자동 승인하지 않는다. 태그·규칙 동시 교정은 최종 조합의 검증 실행을 연결한다.';
COMMENT ON COLUMN "feedback"."resolution_note" IS '검수 판단 이유·적용 범위·현재 처리 결과. 교정 규칙에는 같은 사유를 중복 저장하지 않고 이 피드백을 참조한다. 전체 활성·비활성 사건 감사 로그로 사용하지 않는다.';
COMMENT ON COLUMN "feedback"."created_at" IS '신고한 시각';
COMMENT ON COLUMN "feedback"."review_started_at" IS '검수를 시작한 시각. 평가 지표의 검수 시간을 재는 데 쓴다';
COMMENT ON COLUMN "feedback"."closed_at" IS '종료 시각';
COMMENT ON COLUMN "feedback"."updated_at" IS '마지막 수정 시각';

COMMENT ON TABLE "search_rule" IS '신고에서 만든 장면 제외(exclude_scene) 또는 해석 패턴 교정(patch_parse) 규칙. 본문 변경은 새 규칙으로 검증·교체한다.';
COMMENT ON COLUMN "search_rule"."search_rule_id" IS '교정 규칙 고유 번호이자 불변 본문의 식별자. 조건·패치를 수정하면 새 ID로 후보를 만들고 검증 후 교체한다. 별도 버전 체인은 만들지 않는다.';
COMMENT ON COLUMN "search_rule"."query_fingerprint" IS '정규화한 질의와 필터의 SHA-256. exclude_scene의 exact 매칭 조건. patch_parse에서는 원인 검색 기록이며 매칭은 condition_json으로 resolver 원본 출력을 판정한다. NOT NULL 유지.';
COMMENT ON COLUMN "search_rule"."normalized_query" IS '정규화한 질의. exact 규칙의 검색 조건이며 patch_parse에서는 원인 검색을 확인하는 기록이다. NOT NULL 유지.';
COMMENT ON COLUMN "search_rule"."normalized_filters_json" IS '정규화한 필터. exact 규칙의 검색 조건이며 patch_parse에서는 원인 검색 기록이다. NOT NULL 유지. 패치가 명시적 사용자 필터를 덮어쓰면 안 된다.';
COMMENT ON COLUMN "search_rule"."normalization_version" IS '원인 검색의 정규화 버전. NOT NULL 유지. 정규화 변경은 exact 매칭에 영향을 준다. patch_parse의 조건 문법·출력 계약 호환성은 condition_json의 버전으로 검사한다.';
COMMENT ON COLUMN "search_rule"."action" IS 'exclude_scene(exact 장면 제외) | patch_parse(resolver 원본 출력 조건에 따른 직접 패치). 해석 교정은 patch_parse로 수행한다. 조건·패치 본문은 불변이며 변경 시 새 규칙으로 검증한다.';
COMMENT ON COLUMN "search_rule"."target_scene_id" IS '제외할 장면. exclude_scene에서만 필수이며 patch_parse에서는 NULL.';
COMMENT ON COLUMN "search_rule"."source_feedback_id" IS '원인 피드백. NOT NULL 유지. 규칙은 신고에서만 생성하고 생성자·사유를 원인 피드백에서 조회한다. 후보·개정 규칙이 존재할 수 있으므로 이 컬럼을 UNIQUE로 제한하지 않는다.';
COMMENT ON COLUMN "search_rule"."active" IS '일반 검색에서 사용 가능한 승인 규칙 여부. 검증 트랜잭션 안에 후보를 false로 임시 저장하고 검증 집합에 명시적으로 포함한 뒤 ROLLBACK한다. 검증·검수자 확인 후 승인 규칙을 저장·활성화하며 교체 대상 비활성화도 함께 확정한다. 독립 patch_parse 규칙은 복수 적용 가능.';
COMMENT ON COLUMN "search_rule"."created_at" IS '만든 시각';
COMMENT ON COLUMN "search_rule"."updated_at" IS '마지막 규칙 상태 변경 시각. 본문 변경은 새 규칙 ID로 처리한다. 이 컬럼으로 전체 활성·비활성 사건의 변경자·사유·시각을 복원할 수는 없다.';
COMMENT ON COLUMN "search_rule"."condition_json" IS 'resolver 원본 출력에서 판정할 조건과 문법·출력 계약의 호환성 정보. patch_parse에서 필수. 질의 fingerprint와 별도로 매칭한다. JSON 키·형식과 허용 조건 어휘의 정본은 com.npick.search.domain.model.ParseRule 이다 (S15P21A501-49). 조건이 거는 출력 키는 ai/src/npick_worker/query_resolver/schema.py 기준이다.';
COMMENT ON COLUMN "search_rule"."patch_json" IS '해석에 적용할 허용된 변경 연산 목록. patch_parse에서 필수. 명시적 사용자 필터·출처를 보호하고 실행 코드는 허용하지 않는다. JSON 키·형식과 허용 연산 어휘의 정본은 com.npick.search.domain.model.ParseRule 이다 (S15P21A501-49). 연산은 값 설정·해제와 목록 항목 추가·제거뿐이고 정규식·스크립트·JSON 경로는 문법에 없다.';
