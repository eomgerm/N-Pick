-- N-Pick baseline schema (ERD v1.5)
--
-- 정본
--   논리 명칭·컬럼 구성 : NewsCut-FRD-v2.2 §10
--   다이어그램          : ERDCloud v1.5 (34 테이블 / 396 컬럼 / 76 FK)
--   물리 제약           : 이 파일. ERDCloud import 는 ALTER/CREATE INDEX 를 읽지 못하므로
--                         UNIQUE·CHECK·인덱스는 다이어그램에 없고 여기가 정본이다.
--
-- 설계 결정
--   PK 는 bigint TSID(앱 생성). 서수 의미가 있는 4개만 IDENTITY
--     index_generation.generation_no / search_configuration.config_no
--     external_provider_profile.profile_no / deployment_external_policy.policy_no
--   FK 76개 전부 ON DELETE RESTRICT. 하드 삭제를 하지 않는다.
--     논리 삭제는 clip.deleted_at 과 media_asset.deleted_at 둘뿐이다.
--   enum 어휘 CHECK 은 두지 않는다. 서비스 레이어가 검증하고 DB 는 varchar(32) 길이만 방어한다.
--   updated_at 갱신 트리거를 두지 않는다. JPA @LastModifiedDate 가 책임진다.
--   FK 는 테이블 정의에서 분리해 한 블록에 모았다. clip <-> pipeline_run 상호 참조와
--     search_execution -> feedback_inquiry -> search_result -> search_execution 3단 순환이 있어
--     인라인 참조로는 생성 순서를 만들 수 없다.
--   CREATE INDEX CONCURRENTLY 는 쓰지 않는다. Flyway 가 마이그레이션을 트랜잭션으로 감싼다.
--   baseline 은 단일 마이그레이션이다. 쪼개면 중간 실패 시 테이블만 있고 제약이 없는 상태로 멈춘다.

-- ============================================================================
-- 1. 테이블
-- ============================================================================

CREATE TABLE member (
    member_id    bigint       NOT NULL,
    member_key   varchar(64)  NOT NULL,
    display_name varchar(100) NOT NULL,
    role         varchar(32)  NOT NULL,
    active       boolean      DEFAULT true NOT NULL,
    created_at   timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at   timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_member PRIMARY KEY (member_id),
    CONSTRAINT uq_member_member_key UNIQUE (member_key)
);

CREATE TABLE api_idempotency_request (
    api_request_id     bigint       NOT NULL,
    requested_by_id    bigint       NOT NULL,
    operation          varchar(64)  NOT NULL,
    idempotency_key    varchar(128) NOT NULL,
    request_hash       varchar(64)  NOT NULL,
    status             varchar(32)  NOT NULL,
    response_http_code integer,
    response_body_json jsonb,
    created_at         timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at       timestamptz,
    updated_at         timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    replay_expires_at  timestamptz  NOT NULL,
    CONSTRAINT pk_api_idempotency_request PRIMARY KEY (api_request_id),
    CONSTRAINT uq_api_idempotency_request_key UNIQUE (requested_by_id, operation, idempotency_key)
);

CREATE TABLE media_asset (
    asset_id          bigint       NOT NULL,
    asset_type        varchar(32)  NOT NULL,
    storage_key       text         NOT NULL,
    original_filename varchar(255),
    content_hash      varchar(64)  NOT NULL,
    mime_type         varchar(255) NOT NULL,
    size_bytes        bigint       NOT NULL,
    created_at        timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at        timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at        timestamptz,
    CONSTRAINT pk_media_asset PRIMARY KEY (asset_id)
);

CREATE TABLE clip (
    clip_id                     bigint       NOT NULL,
    source_type                 varchar(32)  NOT NULL,
    source_asset_id             bigint       NOT NULL,
    registration_request_id     bigint       NOT NULL,
    registered_by_id            bigint       NOT NULL,
    media_content_hash          varchar(64)  NOT NULL,
    title                       varchar(500),
    broadcast_date              date,
    filming_date                date,
    license_info_json           jsonb        NOT NULL,
    external_processing_allowed varchar(32)  NOT NULL,
    transcript_source           varchar(32)  NOT NULL,
    reference_text              text,
    serving_status              varchar(32)  NOT NULL,
    active_pipeline_run_id      bigint,
    row_version                 bigint       DEFAULT 0 NOT NULL,
    created_at                  timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at                  timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at                  timestamptz,
    CONSTRAINT pk_clip PRIMARY KEY (clip_id)
);

CREATE TABLE pipeline_run (
    pipeline_run_id          bigint       NOT NULL,
    clip_id                  bigint       NOT NULL,
    request_id               bigint       NOT NULL,
    requested_by_id          bigint       NOT NULL,
    retry_of_pipeline_run_id bigint,
    processing_no            integer      NOT NULL,
    pipeline_version         varchar(128) NOT NULL,
    model_versions_json      jsonb        NOT NULL,
    status                   varchar(32)  NOT NULL,
    warning_count            integer      DEFAULT 0 NOT NULL,
    error_code               varchar(64),
    error_summary            text,
    created_at               timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    started_at               timestamptz,
    finished_at              timestamptz,
    updated_at               timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_pipeline_run PRIMARY KEY (pipeline_run_id),
    CONSTRAINT uq_pipeline_run_clip_processing_no UNIQUE (clip_id, processing_no)
);

CREATE TABLE pipeline_stage (
    pipeline_stage_id   bigint      NOT NULL,
    pipeline_run_id     bigint      NOT NULL,
    stage_name          varchar(32) NOT NULL,
    stage_order         integer     NOT NULL,
    status              varchar(32) NOT NULL,
    fatal               boolean     NOT NULL,
    attempt_count       integer     DEFAULT 0 NOT NULL,
    input_version_json  jsonb       NOT NULL,
    output_version_json jsonb,
    error_code          varchar(64),
    error_summary       text,
    started_at          timestamptz,
    finished_at         timestamptz,
    updated_at          timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at          timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_pipeline_stage PRIMARY KEY (pipeline_stage_id),
    CONSTRAINT uq_pipeline_stage_run_stage_name UNIQUE (pipeline_run_id, stage_name)
);

CREATE TABLE pipeline_stage_attempt (
    pipeline_stage_attempt_id bigint       NOT NULL,
    pipeline_stage_id         bigint       NOT NULL,
    attempt_no                integer      NOT NULL,
    status                    varchar(32)  NOT NULL,
    retryable                 boolean      NOT NULL,
    lease_owner               varchar(128),
    leased_until              timestamptz,
    heartbeat_at              timestamptz,
    input_version_json        jsonb        NOT NULL,
    output_version_json       jsonb,
    error_category            varchar(32),
    error_code                varchar(64),
    error_summary             text,
    started_at                timestamptz  NOT NULL,
    finished_at               timestamptz,
    created_at                timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at                timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_pipeline_stage_attempt PRIMARY KEY (pipeline_stage_attempt_id),
    CONSTRAINT uq_pipeline_stage_attempt_stage_attempt_no UNIQUE (pipeline_stage_id, attempt_no)
);

CREATE TABLE index_generation (
    generation_no           bigint      GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    status                  varchar(32) NOT NULL,
    source_manifest_hash    varchar(64) NOT NULL,
    expected_document_count integer     NOT NULL,
    indexed_document_count  integer     NOT NULL,
    index_config_json       jsonb       NOT NULL,
    error_code              varchar(64),
    error_summary           text,
    created_at              timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ready_at                timestamptz,
    activated_at            timestamptz,
    superseded_at           timestamptz,
    gc_completed_at         timestamptz,
    updated_at              timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_index_generation PRIMARY KEY (generation_no)
);

CREATE TABLE scene (
    scene_id           bigint      NOT NULL,
    clip_id            bigint      NOT NULL,
    pipeline_run_id    bigint      NOT NULL,
    processing_no      integer     NOT NULL,
    scene_index        integer     NOT NULL,
    start_time_ms      bigint      NOT NULL,
    end_time_ms        bigint      NOT NULL,
    thumbnail_asset_id bigint      NOT NULL,
    caption            text,
    scene_type         varchar(32) NOT NULL,
    shot_type          varchar(32) NOT NULL,
    season             varchar(32),
    weather            varchar(32),
    crowd_density      varchar(32),
    created_at         timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_scene PRIMARY KEY (scene_id),
    CONSTRAINT uq_scene_run_scene_index UNIQUE (pipeline_run_id, scene_index)
);

CREATE TABLE frame_asset (
    frame_asset_id     bigint       NOT NULL,
    scene_id           bigint       NOT NULL,
    timestamp_ms       bigint       NOT NULL,
    asset_id           bigint       NOT NULL,
    role               varchar(32)  NOT NULL,
    extraction_version varchar(128) NOT NULL,
    created_at         timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_frame_asset PRIMARY KEY (frame_asset_id)
);

CREATE TABLE transcript_segment (
    transcript_segment_id bigint       NOT NULL,
    clip_id               bigint       NOT NULL,
    pipeline_run_id       bigint       NOT NULL,
    processing_no         integer      NOT NULL,
    segment_index         integer      NOT NULL,
    start_time_ms         bigint       NOT NULL,
    end_time_ms           bigint       NOT NULL,
    verbatim_text         text         NOT NULL,
    source                varchar(32)  NOT NULL,
    source_asset_id       bigint,
    source_locator_json   jsonb,
    confidence            numeric(5,4),
    model_version         varchar(128),
    created_at            timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_transcript_segment PRIMARY KEY (transcript_segment_id),
    CONSTRAINT uq_transcript_segment_run_segment_index UNIQUE (pipeline_run_id, segment_index),
    CONSTRAINT ck_transcript_segment_confidence_range CHECK (confidence BETWEEN 0 AND 1)
);

CREATE TABLE scene_transcript_segment (
    scene_id              bigint      NOT NULL,
    transcript_segment_id bigint      NOT NULL,
    clip_id               bigint      NOT NULL,
    pipeline_run_id       bigint      NOT NULL,
    processing_no         integer     NOT NULL,
    overlap_ms            bigint      NOT NULL,
    created_at            timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_scene_transcript_segment PRIMARY KEY (scene_id, transcript_segment_id)
);

CREATE TABLE ocr_observation (
    ocr_observation_id    bigint       NOT NULL,
    scene_id              bigint       NOT NULL,
    frame_asset_id        bigint       NOT NULL,
    verbatim_text         text         NOT NULL,
    normalized_text       text         NOT NULL,
    confidence            numeric(5,4) NOT NULL,
    bounding_box_json     jsonb        NOT NULL,
    verification_status   varchar(32)  NOT NULL,
    producer_version_json jsonb        NOT NULL,
    created_at            timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at            timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_ocr_observation PRIMARY KEY (ocr_observation_id),
    CONSTRAINT ck_ocr_observation_confidence_range CHECK (confidence BETWEEN 0 AND 1)
);

CREATE TABLE tag (
    tag_id                bigint       NOT NULL,
    tag_type              varchar(32)  NOT NULL,
    canonical_value       varchar(255) NOT NULL,
    display_value         text         NOT NULL,
    normalization_version varchar(128) NOT NULL,
    created_at            timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_tag PRIMARY KEY (tag_id),
    CONSTRAINT uq_tag_canonical UNIQUE (tag_type, canonical_value, normalization_version)
);

CREATE TABLE scene_tag (
    scene_tag_id bigint      NOT NULL,
    scene_id     bigint      NOT NULL,
    tag_id       bigint      NOT NULL,
    created_at   timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_scene_tag PRIMARY KEY (scene_tag_id),
    CONSTRAINT uq_scene_tag_scene_tag UNIQUE (scene_id, tag_id)
);

CREATE TABLE clip_tag (
    clip_tag_id bigint      NOT NULL,
    clip_id     bigint      NOT NULL,
    tag_id      bigint      NOT NULL,
    created_at  timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_clip_tag PRIMARY KEY (clip_tag_id),
    CONSTRAINT uq_clip_tag_clip_tag UNIQUE (clip_id, tag_id)
);

CREATE TABLE field_evidence (
    evidence_id                  bigint       NOT NULL,
    target_type                  varchar(32)  NOT NULL,
    clip_id                      bigint,
    scene_id                     bigint,
    scene_tag_id                 bigint,
    clip_tag_id                  bigint,
    field_name                   varchar(64)  NOT NULL,
    field_value_json             jsonb        NOT NULL,
    source                       varchar(32)  NOT NULL,
    confidence                   numeric(5,4),
    verification_status          varchar(32)  NOT NULL,
    evidence_text                text,
    evidence_asset_id            bigint,
    source_frame_asset_id        bigint,
    source_ocr_observation_id    bigint,
    source_transcript_segment_id bigint,
    source_locator_json          jsonb,
    pipeline_run_id              bigint,
    producer_version_json        jsonb        NOT NULL,
    created_at                   timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at                   timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_field_evidence PRIMARY KEY (evidence_id),
    CONSTRAINT ck_field_evidence_target_single CHECK ((target_type = 'clip'      AND clip_id      IS NOT NULL AND scene_id IS NULL AND scene_tag_id IS NULL AND clip_tag_id IS NULL)
        OR (target_type = 'scene'     AND scene_id     IS NOT NULL AND clip_id  IS NULL AND scene_tag_id IS NULL AND clip_tag_id IS NULL)
        OR (target_type = 'scene_tag' AND scene_tag_id IS NOT NULL AND clip_id  IS NULL AND scene_id     IS NULL AND clip_tag_id IS NULL)
        OR (target_type = 'clip_tag'  AND clip_tag_id  IS NOT NULL AND clip_id  IS NULL AND scene_id     IS NULL AND scene_tag_id IS NULL)),
    CONSTRAINT ck_field_evidence_confidence_range CHECK (confidence BETWEEN 0 AND 1)
);

CREATE TABLE search_index_state (
    scene_id            bigint      NOT NULL,
    index_generation_no bigint      NOT NULL,
    status              varchar(32) NOT NULL,
    attempt_count       integer     DEFAULT 0 NOT NULL,
    error_code          varchar(64),
    searchable_at       timestamptz,
    superseded_at       timestamptz,
    updated_at          timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at          timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_search_index_state PRIMARY KEY (scene_id, index_generation_no)
);

CREATE TABLE index_outbox (
    outbox_id           bigint       NOT NULL,
    event_key           varchar(160) NOT NULL,
    event_type          varchar(32)  NOT NULL,
    index_generation_no bigint       NOT NULL,
    scene_id            bigint,
    status              varchar(32)  NOT NULL,
    lease_owner         varchar(128),
    leased_until        timestamptz,
    attempt_count       integer      DEFAULT 0 NOT NULL,
    next_attempt_at     timestamptz,
    last_error          text,
    created_at          timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    processed_at        timestamptz,
    updated_at          timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_index_outbox PRIMARY KEY (outbox_id),
    CONSTRAINT uq_index_outbox_event_key UNIQUE (event_key)
);

CREATE TABLE search_configuration (
    config_no                 bigint       GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    index_generation_no       bigint       NOT NULL,
    normalization_version     varchar(128) NOT NULL,
    resolution_schema_version varchar(128) NOT NULL,
    guard_policy_version      varchar(128) NOT NULL,
    parameters_json           jsonb        NOT NULL,
    created_at                timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_search_configuration PRIMARY KEY (config_no),
    CONSTRAINT uq_search_configuration_config_generation UNIQUE (config_no, index_generation_no)
);

CREATE TABLE search_session (
    search_session_id       bigint       NOT NULL,
    editor_id               bigint       NOT NULL,
    original_query          text         NOT NULL,
    canonical_query         text         NOT NULL,
    explicit_filters_json   jsonb        NOT NULL,
    canonical_filters_json  jsonb        NOT NULL,
    canonical_scope_payload text         NOT NULL,
    query_fingerprint       varchar(64)  NOT NULL,
    normalization_version   varchar(128) NOT NULL,
    created_at              timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_search_session PRIMARY KEY (search_session_id)
);

CREATE TABLE search_execution (
    search_execution_id     bigint       NOT NULL,
    search_session_id       bigint       NOT NULL,
    request_id              bigint       NOT NULL,
    requested_by_id         bigint       NOT NULL,
    replay_of_inquiry_id    bigint,
    execution_type          varchar(32)  NOT NULL,
    status                  varchar(32)  NOT NULL,
    resolver_status         varchar(32)  NOT NULL,
    fallback_used           boolean      DEFAULT false NOT NULL,
    degraded_reasons_json   jsonb        DEFAULT '[]'::jsonb NOT NULL,
    error_code              varchar(64),
    search_configuration_no bigint       NOT NULL,
    index_generation_no     bigint       NOT NULL,
    lease_owner             varchar(128),
    leased_until            timestamptz,
    heartbeat_at            timestamptz,
    resolver_latency_ms     integer,
    started_at              timestamptz  NOT NULL,
    finished_at             timestamptz,
    created_at              timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_search_execution PRIMARY KEY (search_execution_id),
    CONSTRAINT uq_search_execution_config_generation UNIQUE (search_execution_id, search_configuration_no, index_generation_no),
    CONSTRAINT ck_search_execution_replay_pair CHECK ((execution_type = 'reviewer_replay') = (replay_of_inquiry_id IS NOT NULL))
);

CREATE TABLE search_result_snapshot (
    result_snapshot_id      bigint      NOT NULL,
    search_execution_id     bigint      NOT NULL,
    pre_guard_results_json  jsonb       NOT NULL,
    guard_decisions_json    jsonb       NOT NULL,
    applied_excludes_json   jsonb       NOT NULL,
    final_results_json      jsonb       NOT NULL,
    search_configuration_no bigint      NOT NULL,
    index_generation_no     bigint      NOT NULL,
    created_at              timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_search_result_snapshot PRIMARY KEY (result_snapshot_id),
    CONSTRAINT uq_search_result_snapshot_execution UNIQUE (search_execution_id)
);

CREATE TABLE search_result (
    search_result_id     bigint      NOT NULL,
    search_execution_id  bigint      NOT NULL,
    scene_id             bigint      NOT NULL,
    scene_processing_no  integer     NOT NULL,
    result_rank          integer     NOT NULL,
    score_breakdown_json jsonb       NOT NULL,
    match_snapshot_json  jsonb       NOT NULL,
    guard_snapshot_json  jsonb       NOT NULL,
    created_at           timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_search_result PRIMARY KEY (search_result_id),
    CONSTRAINT uq_search_result_execution_rank UNIQUE (search_execution_id, result_rank)
);

CREATE TABLE feedback_inquiry (
    inquiry_id             bigint       NOT NULL,
    search_result_id       bigint       NOT NULL,
    editor_id              bigint       NOT NULL,
    context_snapshot_json  jsonb        NOT NULL,
    context_schema_version varchar(128) NOT NULL,
    comment                text,
    idempotency_key        varchar(128) NOT NULL,
    status                 varchar(32)  NOT NULL,
    reviewer_id            bigint,
    row_version            bigint       DEFAULT 0 NOT NULL,
    created_at             timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    review_started_at      timestamptz,
    closed_at              timestamptz,
    updated_at             timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_feedback_inquiry PRIMARY KEY (inquiry_id)
);

CREATE TABLE pinned_override (
    override_id                bigint       NOT NULL,
    query_fingerprint          varchar(64)  NOT NULL,
    canonical_query            text         NOT NULL,
    canonical_filters_json     jsonb        NOT NULL,
    canonical_scope_payload    text         NOT NULL,
    normalization_version      varchar(128) NOT NULL,
    action                     varchar(32)  NOT NULL,
    target_scene_id            bigint,
    target_scene_processing_no integer,
    resolution_value_json      jsonb,
    resolution_schema_version  varchar(128),
    source_inquiry_id          bigint       NOT NULL,
    reason                     text         NOT NULL,
    reviewer_id                bigint       NOT NULL,
    revision_no                integer      NOT NULL,
    row_version                bigint       DEFAULT 0 NOT NULL,
    supersedes_override_id     bigint,
    lifecycle_status           varchar(32)  NOT NULL,
    status_reason              text,
    created_at                 timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    verified_at                timestamptz,
    activated_at               timestamptz,
    deactivated_at             timestamptz,
    updated_at                 timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_pinned_override PRIMARY KEY (override_id),
    CONSTRAINT ck_pinned_override_action_shape CHECK ((action = 'exclude_scene'    AND target_scene_id IS NOT NULL AND resolution_value_json IS NULL)
        OR (action = 'resolution_patch' AND target_scene_id IS NULL     AND resolution_value_json IS NOT NULL)),
    CONSTRAINT ck_pinned_override_target_scene_pair CHECK ((target_scene_id IS NULL) = (target_scene_processing_no IS NULL))
);

CREATE TABLE search_execution_applied_override (
    search_execution_id bigint      NOT NULL,
    override_id         bigint      NOT NULL,
    application_stage   varchar(32) NOT NULL,
    created_at          timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_search_execution_applied_override PRIMARY KEY (search_execution_id, override_id)
);

CREATE TABLE query_resolution_snapshot (
    resolution_snapshot_id          bigint       NOT NULL,
    search_execution_id             bigint       NOT NULL,
    resolution_source               varchar(32)  NOT NULL,
    resolution_value_json           jsonb        NOT NULL,
    explicit_anchor_validation_json jsonb        NOT NULL,
    model_version                   varchar(128),
    prompt_version                  varchar(128),
    resolution_schema_version       varchar(128) NOT NULL,
    normalization_version           varchar(128) NOT NULL,
    applied_override_id             bigint,
    failure_category                varchar(64),
    created_at                      timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_query_resolution_snapshot PRIMARY KEY (resolution_snapshot_id),
    CONSTRAINT uq_query_resolution_snapshot_execution UNIQUE (search_execution_id)
);

CREATE TABLE inquiry_resolution (
    inquiry_id                bigint      NOT NULL,
    resolution_type           varchar(32) NOT NULL,
    override_id               bigint,
    verification_execution_id bigint,
    deferred_target_json      jsonb,
    reviewer_reason           text        NOT NULL,
    created_at                timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_inquiry_resolution PRIMARY KEY (inquiry_id)
);

CREATE TABLE override_lifecycle_history (
    override_lifecycle_history_id bigint      NOT NULL,
    override_id                   bigint      NOT NULL,
    from_status                   varchar(32),
    to_status                     varchar(32) NOT NULL,
    changed_by_id                 bigint,
    changed_by_kind               varchar(32) NOT NULL,
    reason                        text        NOT NULL,
    created_at                    timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_override_lifecycle_history PRIMARY KEY (override_lifecycle_history_id),
    CONSTRAINT ck_override_lifecycle_history_changed_by CHECK ((changed_by_kind = 'user') = (changed_by_id IS NOT NULL))
);

CREATE TABLE inquiry_status_history (
    inquiry_status_history_id bigint      NOT NULL,
    inquiry_id                bigint      NOT NULL,
    from_status               varchar(32),
    to_status                 varchar(32) NOT NULL,
    changed_by_id             bigint,
    changed_by_kind           varchar(32) NOT NULL,
    reason                    text        NOT NULL,
    created_at                timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_inquiry_status_history PRIMARY KEY (inquiry_status_history_id),
    CONSTRAINT ck_inquiry_status_history_changed_by CHECK ((changed_by_kind = 'user') = (changed_by_id IS NOT NULL))
);

CREATE TABLE external_provider_profile (
    profile_no                               bigint       GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    provider_name                            varchar(100) NOT NULL,
    account_alias                            varchar(100) NOT NULL,
    region                                   varchar(64)  NOT NULL,
    profile_schema_version                   varchar(128) NOT NULL,
    endpoint_allowlist_json                  jsonb        NOT NULL,
    component_model_map_json                 jsonb        NOT NULL,
    payload_allowlist_json                   jsonb        NOT NULL,
    rights_evidence_refs_json                jsonb        NOT NULL,
    retention_training_deletion_summary_json jsonb        NOT NULL,
    evidence_reviewed_at                     timestamptz,
    evidence_valid_until                     timestamptz,
    active                                   boolean      DEFAULT false NOT NULL,
    created_at                               timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at                               timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_external_provider_profile PRIMARY KEY (profile_no)
);

CREATE TABLE deployment_external_policy (
    policy_no                         bigint       GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    deployment_scope                  varchar(128) NOT NULL,
    provider_profile_no               bigint       NOT NULL,
    query_external_processing_allowed varchar(32)  NOT NULL,
    approved_by_id                    bigint       NOT NULL,
    approved_at                       timestamptz  NOT NULL,
    active                            boolean      DEFAULT false NOT NULL,
    created_at                        timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at                        timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_deployment_external_policy PRIMARY KEY (policy_no)
);

CREATE TABLE external_call_audit (
    external_call_id        bigint       NOT NULL,
    pipeline_run_id         bigint,
    search_execution_id     bigint,
    provider_profile_no     bigint       NOT NULL,
    deployment_policy_no    bigint       NOT NULL,
    component               varchar(32)  NOT NULL,
    attempt_no              integer      NOT NULL,
    payload_categories_json jsonb        NOT NULL,
    payload_size_bytes      bigint       NOT NULL,
    license_decision        varchar(32)  NOT NULL,
    status                  varchar(32)  NOT NULL,
    provider_request_id     varchar(255),
    error_code              varchar(64),
    started_at              timestamptz  NOT NULL,
    finished_at             timestamptz,
    created_at              timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              timestamptz  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_external_call_audit PRIMARY KEY (external_call_id),
    CONSTRAINT ck_external_call_audit_parent_exactly_one CHECK ((pipeline_run_id IS NOT NULL) <> (search_execution_id IS NOT NULL))
);

-- ============================================================================
-- 2. 외래 키 76개 -- 전부 ON DELETE RESTRICT
-- ============================================================================

ALTER TABLE api_idempotency_request ADD CONSTRAINT fk_api_idempotency_request_requested_by_id
    FOREIGN KEY (requested_by_id) REFERENCES member (member_id) ON DELETE RESTRICT;

ALTER TABLE clip ADD CONSTRAINT fk_clip_source_asset_id
    FOREIGN KEY (source_asset_id) REFERENCES media_asset (asset_id) ON DELETE RESTRICT;
ALTER TABLE clip ADD CONSTRAINT fk_clip_registration_request_id
    FOREIGN KEY (registration_request_id) REFERENCES api_idempotency_request (api_request_id) ON DELETE RESTRICT;
ALTER TABLE clip ADD CONSTRAINT fk_clip_registered_by_id
    FOREIGN KEY (registered_by_id) REFERENCES member (member_id) ON DELETE RESTRICT;
ALTER TABLE clip ADD CONSTRAINT fk_clip_active_pipeline_run_id
    FOREIGN KEY (active_pipeline_run_id) REFERENCES pipeline_run (pipeline_run_id) ON DELETE RESTRICT;

ALTER TABLE pipeline_run ADD CONSTRAINT fk_pipeline_run_clip_id
    FOREIGN KEY (clip_id) REFERENCES clip (clip_id) ON DELETE RESTRICT;
ALTER TABLE pipeline_run ADD CONSTRAINT fk_pipeline_run_request_id
    FOREIGN KEY (request_id) REFERENCES api_idempotency_request (api_request_id) ON DELETE RESTRICT;
ALTER TABLE pipeline_run ADD CONSTRAINT fk_pipeline_run_requested_by_id
    FOREIGN KEY (requested_by_id) REFERENCES member (member_id) ON DELETE RESTRICT;
ALTER TABLE pipeline_run ADD CONSTRAINT fk_pipeline_run_retry_of_pipeline_run_id
    FOREIGN KEY (retry_of_pipeline_run_id) REFERENCES pipeline_run (pipeline_run_id) ON DELETE RESTRICT;

ALTER TABLE pipeline_stage ADD CONSTRAINT fk_pipeline_stage_pipeline_run_id
    FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_run (pipeline_run_id) ON DELETE RESTRICT;

ALTER TABLE pipeline_stage_attempt ADD CONSTRAINT fk_pipeline_stage_attempt_pipeline_stage_id
    FOREIGN KEY (pipeline_stage_id) REFERENCES pipeline_stage (pipeline_stage_id) ON DELETE RESTRICT;

ALTER TABLE scene ADD CONSTRAINT fk_scene_clip_id
    FOREIGN KEY (clip_id) REFERENCES clip (clip_id) ON DELETE RESTRICT;
ALTER TABLE scene ADD CONSTRAINT fk_scene_pipeline_run_id
    FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_run (pipeline_run_id) ON DELETE RESTRICT;
ALTER TABLE scene ADD CONSTRAINT fk_scene_thumbnail_asset_id
    FOREIGN KEY (thumbnail_asset_id) REFERENCES media_asset (asset_id) ON DELETE RESTRICT;

ALTER TABLE frame_asset ADD CONSTRAINT fk_frame_asset_scene_id
    FOREIGN KEY (scene_id) REFERENCES scene (scene_id) ON DELETE RESTRICT;
ALTER TABLE frame_asset ADD CONSTRAINT fk_frame_asset_asset_id
    FOREIGN KEY (asset_id) REFERENCES media_asset (asset_id) ON DELETE RESTRICT;

ALTER TABLE transcript_segment ADD CONSTRAINT fk_transcript_segment_clip_id
    FOREIGN KEY (clip_id) REFERENCES clip (clip_id) ON DELETE RESTRICT;
ALTER TABLE transcript_segment ADD CONSTRAINT fk_transcript_segment_pipeline_run_id
    FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_run (pipeline_run_id) ON DELETE RESTRICT;
ALTER TABLE transcript_segment ADD CONSTRAINT fk_transcript_segment_source_asset_id
    FOREIGN KEY (source_asset_id) REFERENCES media_asset (asset_id) ON DELETE RESTRICT;

ALTER TABLE scene_transcript_segment ADD CONSTRAINT fk_scene_transcript_segment_scene_id
    FOREIGN KEY (scene_id) REFERENCES scene (scene_id) ON DELETE RESTRICT;
ALTER TABLE scene_transcript_segment ADD CONSTRAINT fk_scene_transcript_segment_transcript_segment_id
    FOREIGN KEY (transcript_segment_id) REFERENCES transcript_segment (transcript_segment_id) ON DELETE RESTRICT;
ALTER TABLE scene_transcript_segment ADD CONSTRAINT fk_scene_transcript_segment_clip_id
    FOREIGN KEY (clip_id) REFERENCES clip (clip_id) ON DELETE RESTRICT;
ALTER TABLE scene_transcript_segment ADD CONSTRAINT fk_scene_transcript_segment_pipeline_run_id
    FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_run (pipeline_run_id) ON DELETE RESTRICT;

ALTER TABLE ocr_observation ADD CONSTRAINT fk_ocr_observation_scene_id
    FOREIGN KEY (scene_id) REFERENCES scene (scene_id) ON DELETE RESTRICT;
ALTER TABLE ocr_observation ADD CONSTRAINT fk_ocr_observation_frame_asset_id
    FOREIGN KEY (frame_asset_id) REFERENCES frame_asset (frame_asset_id) ON DELETE RESTRICT;

ALTER TABLE scene_tag ADD CONSTRAINT fk_scene_tag_scene_id
    FOREIGN KEY (scene_id) REFERENCES scene (scene_id) ON DELETE RESTRICT;
ALTER TABLE scene_tag ADD CONSTRAINT fk_scene_tag_tag_id
    FOREIGN KEY (tag_id) REFERENCES tag (tag_id) ON DELETE RESTRICT;

ALTER TABLE clip_tag ADD CONSTRAINT fk_clip_tag_clip_id
    FOREIGN KEY (clip_id) REFERENCES clip (clip_id) ON DELETE RESTRICT;
ALTER TABLE clip_tag ADD CONSTRAINT fk_clip_tag_tag_id
    FOREIGN KEY (tag_id) REFERENCES tag (tag_id) ON DELETE RESTRICT;

ALTER TABLE field_evidence ADD CONSTRAINT fk_field_evidence_clip_id
    FOREIGN KEY (clip_id) REFERENCES clip (clip_id) ON DELETE RESTRICT;
ALTER TABLE field_evidence ADD CONSTRAINT fk_field_evidence_scene_id
    FOREIGN KEY (scene_id) REFERENCES scene (scene_id) ON DELETE RESTRICT;
ALTER TABLE field_evidence ADD CONSTRAINT fk_field_evidence_scene_tag_id
    FOREIGN KEY (scene_tag_id) REFERENCES scene_tag (scene_tag_id) ON DELETE RESTRICT;
ALTER TABLE field_evidence ADD CONSTRAINT fk_field_evidence_clip_tag_id
    FOREIGN KEY (clip_tag_id) REFERENCES clip_tag (clip_tag_id) ON DELETE RESTRICT;
ALTER TABLE field_evidence ADD CONSTRAINT fk_field_evidence_evidence_asset_id
    FOREIGN KEY (evidence_asset_id) REFERENCES media_asset (asset_id) ON DELETE RESTRICT;
ALTER TABLE field_evidence ADD CONSTRAINT fk_field_evidence_source_frame_asset_id
    FOREIGN KEY (source_frame_asset_id) REFERENCES frame_asset (frame_asset_id) ON DELETE RESTRICT;
ALTER TABLE field_evidence ADD CONSTRAINT fk_field_evidence_source_ocr_observation_id
    FOREIGN KEY (source_ocr_observation_id) REFERENCES ocr_observation (ocr_observation_id) ON DELETE RESTRICT;
ALTER TABLE field_evidence ADD CONSTRAINT fk_field_evidence_source_transcript_segment_id
    FOREIGN KEY (source_transcript_segment_id) REFERENCES transcript_segment (transcript_segment_id) ON DELETE RESTRICT;
ALTER TABLE field_evidence ADD CONSTRAINT fk_field_evidence_pipeline_run_id
    FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_run (pipeline_run_id) ON DELETE RESTRICT;

ALTER TABLE search_index_state ADD CONSTRAINT fk_search_index_state_scene_id
    FOREIGN KEY (scene_id) REFERENCES scene (scene_id) ON DELETE RESTRICT;
ALTER TABLE search_index_state ADD CONSTRAINT fk_search_index_state_index_generation_no
    FOREIGN KEY (index_generation_no) REFERENCES index_generation (generation_no) ON DELETE RESTRICT;

ALTER TABLE index_outbox ADD CONSTRAINT fk_index_outbox_index_generation_no
    FOREIGN KEY (index_generation_no) REFERENCES index_generation (generation_no) ON DELETE RESTRICT;
ALTER TABLE index_outbox ADD CONSTRAINT fk_index_outbox_scene_id
    FOREIGN KEY (scene_id) REFERENCES scene (scene_id) ON DELETE RESTRICT;

ALTER TABLE search_configuration ADD CONSTRAINT fk_search_configuration_index_generation_no
    FOREIGN KEY (index_generation_no) REFERENCES index_generation (generation_no) ON DELETE RESTRICT;

ALTER TABLE search_session ADD CONSTRAINT fk_search_session_editor_id
    FOREIGN KEY (editor_id) REFERENCES member (member_id) ON DELETE RESTRICT;

ALTER TABLE search_execution ADD CONSTRAINT fk_search_execution_search_session_id
    FOREIGN KEY (search_session_id) REFERENCES search_session (search_session_id) ON DELETE RESTRICT;
ALTER TABLE search_execution ADD CONSTRAINT fk_search_execution_request_id
    FOREIGN KEY (request_id) REFERENCES api_idempotency_request (api_request_id) ON DELETE RESTRICT;
ALTER TABLE search_execution ADD CONSTRAINT fk_search_execution_requested_by_id
    FOREIGN KEY (requested_by_id) REFERENCES member (member_id) ON DELETE RESTRICT;
ALTER TABLE search_execution ADD CONSTRAINT fk_search_execution_replay_of_inquiry_id
    FOREIGN KEY (replay_of_inquiry_id) REFERENCES feedback_inquiry (inquiry_id) ON DELETE RESTRICT;
ALTER TABLE search_execution ADD CONSTRAINT fk_search_execution_configuration
    FOREIGN KEY (search_configuration_no, index_generation_no) REFERENCES search_configuration (config_no, index_generation_no) ON DELETE RESTRICT;

ALTER TABLE search_result_snapshot ADD CONSTRAINT fk_search_result_snapshot_execution_versions
    FOREIGN KEY (search_execution_id, search_configuration_no, index_generation_no) REFERENCES search_execution (search_execution_id, search_configuration_no, index_generation_no) ON DELETE RESTRICT;

ALTER TABLE search_result ADD CONSTRAINT fk_search_result_search_execution_id
    FOREIGN KEY (search_execution_id) REFERENCES search_execution (search_execution_id) ON DELETE RESTRICT;
ALTER TABLE search_result ADD CONSTRAINT fk_search_result_scene_id
    FOREIGN KEY (scene_id) REFERENCES scene (scene_id) ON DELETE RESTRICT;

ALTER TABLE feedback_inquiry ADD CONSTRAINT fk_feedback_inquiry_search_result_id
    FOREIGN KEY (search_result_id) REFERENCES search_result (search_result_id) ON DELETE RESTRICT;
ALTER TABLE feedback_inquiry ADD CONSTRAINT fk_feedback_inquiry_editor_id
    FOREIGN KEY (editor_id) REFERENCES member (member_id) ON DELETE RESTRICT;
ALTER TABLE feedback_inquiry ADD CONSTRAINT fk_feedback_inquiry_reviewer_id
    FOREIGN KEY (reviewer_id) REFERENCES member (member_id) ON DELETE RESTRICT;

ALTER TABLE pinned_override ADD CONSTRAINT fk_pinned_override_target_scene_id
    FOREIGN KEY (target_scene_id) REFERENCES scene (scene_id) ON DELETE RESTRICT;
ALTER TABLE pinned_override ADD CONSTRAINT fk_pinned_override_source_inquiry_id
    FOREIGN KEY (source_inquiry_id) REFERENCES feedback_inquiry (inquiry_id) ON DELETE RESTRICT;
ALTER TABLE pinned_override ADD CONSTRAINT fk_pinned_override_reviewer_id
    FOREIGN KEY (reviewer_id) REFERENCES member (member_id) ON DELETE RESTRICT;
ALTER TABLE pinned_override ADD CONSTRAINT fk_pinned_override_supersedes_override_id
    FOREIGN KEY (supersedes_override_id) REFERENCES pinned_override (override_id) ON DELETE RESTRICT;

ALTER TABLE search_execution_applied_override ADD CONSTRAINT fk_search_execution_applied_override_search_execution_id
    FOREIGN KEY (search_execution_id) REFERENCES search_execution (search_execution_id) ON DELETE RESTRICT;
ALTER TABLE search_execution_applied_override ADD CONSTRAINT fk_search_execution_applied_override_override_id
    FOREIGN KEY (override_id) REFERENCES pinned_override (override_id) ON DELETE RESTRICT;

ALTER TABLE query_resolution_snapshot ADD CONSTRAINT fk_query_resolution_snapshot_search_execution_id
    FOREIGN KEY (search_execution_id) REFERENCES search_execution (search_execution_id) ON DELETE RESTRICT;
ALTER TABLE query_resolution_snapshot ADD CONSTRAINT fk_query_resolution_snapshot_applied_override
    FOREIGN KEY (search_execution_id, applied_override_id) REFERENCES search_execution_applied_override (search_execution_id, override_id) ON DELETE RESTRICT;

ALTER TABLE inquiry_resolution ADD CONSTRAINT fk_inquiry_resolution_inquiry_id
    FOREIGN KEY (inquiry_id) REFERENCES feedback_inquiry (inquiry_id) ON DELETE RESTRICT;
ALTER TABLE inquiry_resolution ADD CONSTRAINT fk_inquiry_resolution_override_id
    FOREIGN KEY (override_id) REFERENCES pinned_override (override_id) ON DELETE RESTRICT;
ALTER TABLE inquiry_resolution ADD CONSTRAINT fk_inquiry_resolution_verification_execution_id
    FOREIGN KEY (verification_execution_id) REFERENCES search_execution (search_execution_id) ON DELETE RESTRICT;

ALTER TABLE override_lifecycle_history ADD CONSTRAINT fk_override_lifecycle_history_override_id
    FOREIGN KEY (override_id) REFERENCES pinned_override (override_id) ON DELETE RESTRICT;
ALTER TABLE override_lifecycle_history ADD CONSTRAINT fk_override_lifecycle_history_changed_by_id
    FOREIGN KEY (changed_by_id) REFERENCES member (member_id) ON DELETE RESTRICT;

ALTER TABLE inquiry_status_history ADD CONSTRAINT fk_inquiry_status_history_inquiry_id
    FOREIGN KEY (inquiry_id) REFERENCES feedback_inquiry (inquiry_id) ON DELETE RESTRICT;
ALTER TABLE inquiry_status_history ADD CONSTRAINT fk_inquiry_status_history_changed_by_id
    FOREIGN KEY (changed_by_id) REFERENCES member (member_id) ON DELETE RESTRICT;

ALTER TABLE deployment_external_policy ADD CONSTRAINT fk_deployment_external_policy_provider_profile_no
    FOREIGN KEY (provider_profile_no) REFERENCES external_provider_profile (profile_no) ON DELETE RESTRICT;
ALTER TABLE deployment_external_policy ADD CONSTRAINT fk_deployment_external_policy_approved_by_id
    FOREIGN KEY (approved_by_id) REFERENCES member (member_id) ON DELETE RESTRICT;

ALTER TABLE external_call_audit ADD CONSTRAINT fk_external_call_audit_pipeline_run_id
    FOREIGN KEY (pipeline_run_id) REFERENCES pipeline_run (pipeline_run_id) ON DELETE RESTRICT;
ALTER TABLE external_call_audit ADD CONSTRAINT fk_external_call_audit_search_execution_id
    FOREIGN KEY (search_execution_id) REFERENCES search_execution (search_execution_id) ON DELETE RESTRICT;
ALTER TABLE external_call_audit ADD CONSTRAINT fk_external_call_audit_provider_profile_no
    FOREIGN KEY (provider_profile_no) REFERENCES external_provider_profile (profile_no) ON DELETE RESTRICT;
ALTER TABLE external_call_audit ADD CONSTRAINT fk_external_call_audit_deployment_policy_no
    FOREIGN KEY (deployment_policy_no) REFERENCES deployment_external_policy (policy_no) ON DELETE RESTRICT;

-- ============================================================================
-- 3. 부분 유니크 인덱스 -- UNIQUE 제약으로 표현할 수 없는 조건부 유일성
-- ============================================================================

CREATE UNIQUE INDEX uq_index_generation_active ON index_generation (status) WHERE status = 'active';
CREATE UNIQUE INDEX uq_media_asset_content_hash_alive ON media_asset (content_hash) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_media_asset_storage_key_alive ON media_asset (storage_key) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_deployment_external_policy_active_scope ON deployment_external_policy (deployment_scope) WHERE active;

-- ============================================================================
-- 4. FK 인덱스 -- Postgres 는 FK 에 인덱스를 자동 생성하지 않는다.
--    PK·UNIQUE·워크로드 인덱스의 선행 컬럼으로 이미 덮이는 FK 는 제외했다.
-- ============================================================================

CREATE INDEX ix_clip_source_asset_id ON clip (source_asset_id);
CREATE INDEX ix_clip_registration_request_id ON clip (registration_request_id);
CREATE INDEX ix_clip_registered_by_id ON clip (registered_by_id);
CREATE INDEX ix_clip_active_pipeline_run_id ON clip (active_pipeline_run_id);
CREATE INDEX ix_pipeline_run_request_id ON pipeline_run (request_id);
CREATE INDEX ix_pipeline_run_requested_by_id ON pipeline_run (requested_by_id);
CREATE INDEX ix_pipeline_run_retry_of_pipeline_run_id ON pipeline_run (retry_of_pipeline_run_id);
CREATE INDEX ix_scene_clip_id ON scene (clip_id);
CREATE INDEX ix_scene_thumbnail_asset_id ON scene (thumbnail_asset_id);
CREATE INDEX ix_frame_asset_scene_id ON frame_asset (scene_id);
CREATE INDEX ix_frame_asset_asset_id ON frame_asset (asset_id);
CREATE INDEX ix_transcript_segment_clip_id ON transcript_segment (clip_id);
CREATE INDEX ix_transcript_segment_source_asset_id ON transcript_segment (source_asset_id);
CREATE INDEX ix_scene_transcript_segment_transcript_segment_id ON scene_transcript_segment (transcript_segment_id);
CREATE INDEX ix_scene_transcript_segment_clip_id ON scene_transcript_segment (clip_id);
CREATE INDEX ix_scene_transcript_segment_pipeline_run_id ON scene_transcript_segment (pipeline_run_id);
CREATE INDEX ix_ocr_observation_scene_id ON ocr_observation (scene_id);
CREATE INDEX ix_ocr_observation_frame_asset_id ON ocr_observation (frame_asset_id);
CREATE INDEX ix_scene_tag_tag_id ON scene_tag (tag_id);
CREATE INDEX ix_clip_tag_tag_id ON clip_tag (tag_id);
CREATE INDEX ix_field_evidence_clip_id ON field_evidence (clip_id);
CREATE INDEX ix_field_evidence_scene_id ON field_evidence (scene_id);
CREATE INDEX ix_field_evidence_scene_tag_id ON field_evidence (scene_tag_id);
CREATE INDEX ix_field_evidence_clip_tag_id ON field_evidence (clip_tag_id);
CREATE INDEX ix_field_evidence_evidence_asset_id ON field_evidence (evidence_asset_id);
CREATE INDEX ix_field_evidence_source_frame_asset_id ON field_evidence (source_frame_asset_id);
CREATE INDEX ix_field_evidence_source_ocr_observation_id ON field_evidence (source_ocr_observation_id);
CREATE INDEX ix_field_evidence_source_transcript_segment_id ON field_evidence (source_transcript_segment_id);
CREATE INDEX ix_field_evidence_pipeline_run_id ON field_evidence (pipeline_run_id);
CREATE INDEX ix_index_outbox_index_generation_no ON index_outbox (index_generation_no);
CREATE INDEX ix_index_outbox_scene_id ON index_outbox (scene_id);
CREATE INDEX ix_search_configuration_index_generation_no ON search_configuration (index_generation_no);
CREATE INDEX ix_search_session_editor_id ON search_session (editor_id);
CREATE INDEX ix_search_execution_search_session_id ON search_execution (search_session_id);
CREATE INDEX ix_search_execution_request_id ON search_execution (request_id);
CREATE INDEX ix_search_execution_requested_by_id ON search_execution (requested_by_id);
CREATE INDEX ix_search_execution_replay_of_inquiry_id ON search_execution (replay_of_inquiry_id);
CREATE INDEX ix_search_execution_configuration ON search_execution (search_configuration_no, index_generation_no);
CREATE INDEX ix_search_result_scene_id ON search_result (scene_id);
CREATE INDEX ix_feedback_inquiry_search_result_id ON feedback_inquiry (search_result_id);
CREATE INDEX ix_feedback_inquiry_editor_id ON feedback_inquiry (editor_id);
CREATE INDEX ix_feedback_inquiry_reviewer_id ON feedback_inquiry (reviewer_id);
CREATE INDEX ix_pinned_override_target_scene_id ON pinned_override (target_scene_id);
CREATE INDEX ix_pinned_override_source_inquiry_id ON pinned_override (source_inquiry_id);
CREATE INDEX ix_pinned_override_reviewer_id ON pinned_override (reviewer_id);
CREATE INDEX ix_pinned_override_supersedes_override_id ON pinned_override (supersedes_override_id);
CREATE INDEX ix_search_execution_applied_override_override_id ON search_execution_applied_override (override_id);
CREATE INDEX ix_inquiry_resolution_override_id ON inquiry_resolution (override_id);
CREATE INDEX ix_inquiry_resolution_verification_execution_id ON inquiry_resolution (verification_execution_id);
CREATE INDEX ix_override_lifecycle_history_override_id ON override_lifecycle_history (override_id);
CREATE INDEX ix_override_lifecycle_history_changed_by_id ON override_lifecycle_history (changed_by_id);
CREATE INDEX ix_inquiry_status_history_inquiry_id ON inquiry_status_history (inquiry_id);
CREATE INDEX ix_inquiry_status_history_changed_by_id ON inquiry_status_history (changed_by_id);
CREATE INDEX ix_deployment_external_policy_provider_profile_no ON deployment_external_policy (provider_profile_no);
CREATE INDEX ix_deployment_external_policy_approved_by_id ON deployment_external_policy (approved_by_id);
CREATE INDEX ix_external_call_audit_pipeline_run_id ON external_call_audit (pipeline_run_id);
CREATE INDEX ix_external_call_audit_search_execution_id ON external_call_audit (search_execution_id);
CREATE INDEX ix_external_call_audit_provider_profile_no ON external_call_audit (provider_profile_no);
CREATE INDEX ix_external_call_audit_deployment_policy_no ON external_call_audit (deployment_policy_no);

-- ============================================================================
-- 5. 워커 폴링·조회 경로 인덱스
-- ============================================================================

CREATE INDEX ix_index_outbox_queued ON index_outbox (next_attempt_at) WHERE status = 'queued';
CREATE INDEX ix_index_outbox_processing ON index_outbox (leased_until) WHERE status = 'processing';
CREATE INDEX ix_pipeline_stage_attempt_running ON pipeline_stage_attempt (leased_until) WHERE status = 'running';
CREATE INDEX ix_search_execution_running ON search_execution (leased_until) WHERE status = 'running';
CREATE INDEX ix_search_index_state_generation_status ON search_index_state (index_generation_no, status);
CREATE INDEX ix_pinned_override_active_scope ON pinned_override (query_fingerprint, normalization_version) WHERE lifecycle_status = 'active';
CREATE INDEX ix_search_session_query_fingerprint ON search_session (query_fingerprint);

-- ============================================================================
-- 6. 테이블 주석 -- ERDCloud v1.5 메모 정본
-- ============================================================================

COMMENT ON TABLE member IS '편집기자(editor)·검수자(reviewer) 계정. v1.5 에서 app_actor 를 개명하고 가짜 system 행을 제거했다';
COMMENT ON TABLE api_idempotency_request IS '같은 요청이 두 번 실행되지 않게 막고 재요청에 같은 응답을 돌려준다. 자식 3개가 RESTRICT 로 붙잡아 행을 지울 수 없으므로 replay_expires_at 이후 response_body_json 만 비운다';
COMMENT ON TABLE media_asset IS '영상·프레임·자막 파일 1개. content_hash 와 storage_key 가 각각 유니크(논리 삭제 제외)';
COMMENT ON TABLE clip IS '등록된 영상 1건. 논리 삭제 대상 2개 중 하나(deleted_at). v1.0 은 clip 이 pipeline_run 의 자식으로 잘못 그려져 있었고 v1.5 에서 교정했다';
COMMENT ON TABLE pipeline_run IS 'clip 1건에 대한 분석 실행 1회. clip.active_pipeline_run_id 가 현재 검색에 제공 중인 run 을 가리킨다(clip 당 1개가 구조적으로 보장)';
COMMENT ON TABLE pipeline_stage IS 'run 안의 처리 단계. 정의된 10개';
COMMENT ON TABLE pipeline_stage_attempt IS '단계의 실제 실행·재시도. lease_owner/leased_until 로 동시성을 제어한다';
COMMENT ON TABLE index_generation IS '인덱스 전체 세대. status=''active'' 는 전역 1건(부분 유니크 인덱스)';
COMMENT ON TABLE scene IS '영상의 시간 구간. 검색 결과의 단위';
COMMENT ON TABLE frame_asset IS 'scene 에서 뽑은 프레임';
COMMENT ON TABLE transcript_segment IS '자막·CC·ASR 구간';
COMMENT ON TABLE scene_transcript_segment IS 'scene 과 transcript_segment 를 잇는 연결 테이블';
COMMENT ON TABLE ocr_observation IS 'frame 에서 읽은 글자';
COMMENT ON TABLE tag IS '인물·기관·장소 공통 태그 사전';
COMMENT ON TABLE scene_tag IS 'scene 과 tag 를 잇는 연결 테이블. field_evidence 가 이 링크를 근거 대상으로 참조한다';
COMMENT ON TABLE clip_tag IS 'clip 과 tag 를 잇는 연결 테이블';
COMMENT ON TABLE field_evidence IS '어떤 값이 왜 그렇게 정해졌는지에 대한 근거. target_type 과 일치하는 FK 1개만 채워진다(CHECK)';
COMMENT ON TABLE search_index_state IS 'scene 과 세대를 잇는 세대별 색인 상태';
COMMENT ON TABLE index_outbox IS '색인 반영 작업 큐(outbox 패턴). DB 커밋과 외부 색인을 분리한다';
COMMENT ON TABLE search_configuration IS '검색 파라미터 구성. 색인 세대와 묶여 UNIQUE 이고 search_execution 의 복합 FK 대상이다';
COMMENT ON TABLE search_session IS '편집기자의 검색 1건. query_fingerprint 로 exact override 를 찾는다';
COMMENT ON TABLE search_execution IS '검색 실행 1회. 같은 session 이라도 replay 하면 새 행이 생긴다';
COMMENT ON TABLE search_result_snapshot IS '후보·판정 전체 기록. 실행당 0..1. 3컬럼 복합 FK 로 ''스냅샷의 버전 = 실행 당시 버전''을 DB 가 보장한다';
COMMENT ON TABLE search_result IS '최종 결과 1건(장면 1개). 문의의 출발점';
COMMENT ON TABLE feedback_inquiry IS '''이 결과 이상해요'' 문의. 교정값 자체가 아니다';
COMMENT ON TABLE pinned_override IS '검색어 교정 또는 장면 제외 규칙. 문의에서만 생성된다';
COMMENT ON TABLE search_execution_applied_override IS 'search_execution 과 pinned_override 를 잇는 N:M 연결 테이블';
COMMENT ON TABLE query_resolution_snapshot IS '검색어 해석 기록. 실행당 0..1. v1.0 은 링크 테이블만 경유해 override 가 적용된 실행에서만 존재할 수 있었다 — v1.5 교정';
COMMENT ON TABLE inquiry_resolution IS '문의당 최종 조치 0..1';
COMMENT ON TABLE override_lifecycle_history IS 'override 상태 전환 이력(append-only)';
COMMENT ON TABLE inquiry_status_history IS '문의 상태 전환 이력(append-only)';
COMMENT ON TABLE external_provider_profile IS '외부 AI 제공자 설정의 개정';
COMMENT ON TABLE deployment_external_policy IS '배포별 외부 전송 정책. scope 당 active 1건(부분 유니크 인덱스)';
COMMENT ON TABLE external_call_audit IS '외부 호출 감사. pipeline_run 또는 search_execution 중 정확히 하나에 매달린다(CHECK)';

-- ============================================================================
-- 7. 컬럼 주석 -- ERDCloud v1.5 정본
-- ============================================================================

COMMENT ON COLUMN member.member_id IS 'PK. TSID(bigint, 앱 생성). 편집기자·검수자 계정 식별자';
COMMENT ON COLUMN member.member_key IS '불변 업무 키. 설정·seed 가 이 값으로 계정을 찾는다. UNIQUE';
COMMENT ON COLUMN member.display_name IS 'UI 표시명. 변경 가능하므로 식별에 쓰지 말 것';
COMMENT ON COLUMN member.role IS '`editor` 편집기자(검색·문의) / `reviewer` 검수자(등록·보정). v1.5 에서 system 제거 — 시스템 전환은 history 의 changed_by_kind 로 표현';
COMMENT ON COLUMN member.active IS '계정 사용 가능 여부. false 가 논리 삭제 역할을 하므로 deleted_at 을 두지 않았다';
COMMENT ON COLUMN member.created_at IS '계정 생성 시각. JPA @CreatedDate';
COMMENT ON COLUMN member.updated_at IS '최종 변경 시각. JPA @LastModifiedDate (DB 트리거 없음)';

COMMENT ON COLUMN api_idempotency_request.api_request_id IS 'PK. 멱등 요청 1건';
COMMENT ON COLUMN api_idempotency_request.requested_by_id IS 'FK member. 요청을 보낸 계정';
COMMENT ON COLUMN api_idempotency_request.operation IS '`clip.create` `clip.retry` `search.execute`. 멱등 키의 유효 범위를 가른다';
COMMENT ON COLUMN api_idempotency_request.idempotency_key IS '클라이언트가 생성한 키. UNIQUE(requested_by_id, operation, idempotency_key)';
COMMENT ON COLUMN api_idempotency_request.request_hash IS '정규화한 요청 본문의 SHA-256 hex. 같은 키로 다른 본문이 오면 충돌로 판정';
COMMENT ON COLUMN api_idempotency_request.status IS '`in_progress` `succeeded` `failed`. in_progress 중 재요청은 대기시킨다';
COMMENT ON COLUMN api_idempotency_request.response_http_code IS '완료 시 HTTP 상태 코드. integer 다 — 다른 varchar status 와 혼동하지 말 것(v1.5 에서 response_status 에서 개명)';
COMMENT ON COLUMN api_idempotency_request.response_body_json IS '재응답 본문. secret·원문 media 제외. replay_expires_at 이 지나면 배치가 NULL 로 비운다';
COMMENT ON COLUMN api_idempotency_request.created_at IS '최초 수신 시각';
COMMENT ON COLUMN api_idempotency_request.completed_at IS 'terminal 도달 시각. NULL 이면 아직 처리 중';
COMMENT ON COLUMN api_idempotency_request.updated_at IS '상태 갱신 시각';
COMMENT ON COLUMN api_idempotency_request.replay_expires_at IS '재응답 보관 만료. 행 자체는 clip·pipeline_run·search_execution 이 RESTRICT 로 붙잡아 지울 수 없으므로 payload 만 비운다';

COMMENT ON COLUMN media_asset.asset_id IS 'PK. 파일 1개';
COMMENT ON COLUMN media_asset.asset_type IS '`source_video` `frame` `thumbnail` `transcript_file`';
COMMENT ON COLUMN media_asset.storage_key IS 'media-root 기준 상대 경로. 클라이언트가 준 절대 경로는 절대 받지 않는다(FR-ING-009). 논리 삭제 제외 UNIQUE';
COMMENT ON COLUMN media_asset.original_filename IS '경로를 제거한 안전한 원본 파일명. 표시용';
COMMENT ON COLUMN media_asset.content_hash IS '파일 SHA-256 hex. 논리 삭제 제외 UNIQUE 로 중복 업로드를 막는다';
COMMENT ON COLUMN media_asset.mime_type IS '서버가 sniff 로 판정한 MIME. 확장자를 신뢰하지 않는다. 파라미터(; charset=) 제외하고 type/subtype 만 저장';
COMMENT ON COLUMN media_asset.size_bytes IS '파일 크기(byte)';
COMMENT ON COLUMN media_asset.created_at IS '등록 시각';
COMMENT ON COLUMN media_asset.updated_at IS '변경 시각. deleted_at 이 바뀌므로 필요하다';
COMMENT ON COLUMN media_asset.deleted_at IS '논리 삭제 시각. NULL 이면 유효. 물리 파일 GC 후에도 행은 남아 감사 링크를 보존한다';

COMMENT ON COLUMN clip.clip_id IS 'PK. 등록된 영상 1건. v1.0 에서 잘못 FK 로 표시돼 pipeline_run 의 자식이 돼 있던 것을 v1.5 에서 교정';
COMMENT ON COLUMN clip.source_type IS 'P0 는 `broadcast` 고정';
COMMENT ON COLUMN clip.source_asset_id IS 'FK media_asset. 원본 video 파일';
COMMENT ON COLUMN clip.registration_request_id IS 'FK api_idempotency_request. 이 clip 을 만든 등록 요청';
COMMENT ON COLUMN clip.registered_by_id IS 'FK member. 등록한 검수자';
COMMENT ON COLUMN clip.media_content_hash IS '원본 media SHA-256. media_asset 행이 만들어지기 전 등록 시점 중복 판정에 쓴다(FR-ING-008)';
COMMENT ON COLUMN clip.title IS '사용자가 입력한 원 제목. 검색 신뢰 대상이 아니다';
COMMENT ON COLUMN clip.broadcast_date IS '방송일. 불명이면 NULL';
COMMENT ON COLUMN clip.filming_date IS '촬영일. 불명이면 NULL';
COMMENT ON COLUMN clip.license_info_json IS '권리 원천·사용 범위·표시 문구. 감사 대상이라 삭제하지 않는다';
COMMENT ON COLUMN clip.external_processing_allowed IS '`yes` `no` `unknown`. 외부 AI 전송 가부 판정의 clip 측 입력';
COMMENT ON COLUMN clip.transcript_source IS 'active run 요약값 `provided` `cc` `asr` `none`. 정본은 transcript_segment.source';
COMMENT ON COLUMN clip.reference_text IS 'timestamp 없는 참고 대본. 구간 매칭에 쓰지 않는다';
COMMENT ON COLUMN clip.serving_status IS '`queued` `running` `ready` `failed`. ready 여야 검색에 노출된다';
COMMENT ON COLUMN clip.active_pipeline_run_id IS 'FK pipeline_run. 현재 검색에 제공 중인 run. 컬럼이 1개라 clip 당 active run 1개가 구조적으로 보장된다';
COMMENT ON COLUMN clip.row_version IS 'JPA @Version 낙관적 락. 서수가 아니다';
COMMENT ON COLUMN clip.created_at IS '등록 시각';
COMMENT ON COLUMN clip.updated_at IS '변경 시각';
COMMENT ON COLUMN clip.deleted_at IS '논리 삭제 시각. NULL 이면 유효. 검색 경로 쿼리는 반드시 이 조건을 건다(@SQLRestriction 은 감사 경로를 끊으므로 쓰지 않는다)';

COMMENT ON COLUMN pipeline_run.pipeline_run_id IS 'PK. clip 1건에 대한 분석 실행 1회';
COMMENT ON COLUMN pipeline_run.clip_id IS 'FK clip. v1.0 은 이 컬럼이 자기 자신을 가리키는 self-loop 였다 — v1.5 교정';
COMMENT ON COLUMN pipeline_run.request_id IS 'FK api_idempotency_request. 등록 또는 재시도 요청';
COMMENT ON COLUMN pipeline_run.requested_by_id IS 'FK member. 실행을 요청한 검수자';
COMMENT ON COLUMN pipeline_run.retry_of_pipeline_run_id IS 'FK 자기 참조. 명시적 재시도의 직전 run. 자동 재시도는 attempt 레벨에서 처리';
COMMENT ON COLUMN pipeline_run.processing_no IS 'clip 안에서 몇 번째 분석인지. UNIQUE(clip_id, processing_no). 검수 화면에 노출된다';
COMMENT ON COLUMN pipeline_run.pipeline_version IS '파이프라인 코드·설정 버전 문자열. processing_no(정수 순번)와 다른 개념이라 v1.5 에서 개명';
COMMENT ON COLUMN pipeline_run.model_versions_json IS 'stage 별 model/prompt/schema 버전 묶음';
COMMENT ON COLUMN pipeline_run.status IS '`queued` `running` `succeeded` `failed`';
COMMENT ON COLUMN pipeline_run.warning_count IS '비치명 경고 수. 실패로 보지 않는다';
COMMENT ON COLUMN pipeline_run.error_code IS 'run 최종 오류 코드';
COMMENT ON COLUMN pipeline_run.error_summary IS '민감정보를 제거한 오류 요약';
COMMENT ON COLUMN pipeline_run.created_at IS '큐 등록 시각. started_at 과 다르다';
COMMENT ON COLUMN pipeline_run.started_at IS '실행 시작 시각';
COMMENT ON COLUMN pipeline_run.finished_at IS '종료 시각';
COMMENT ON COLUMN pipeline_run.updated_at IS '상태 갱신 시각';

COMMENT ON COLUMN pipeline_stage.pipeline_stage_id IS 'PK. run 안의 처리 단계 1개';
COMMENT ON COLUMN pipeline_stage.pipeline_run_id IS 'FK pipeline_run';
COMMENT ON COLUMN pipeline_stage.stage_name IS '정의된 10개 stage 중 하나. UNIQUE(pipeline_run_id, stage_name)';
COMMENT ON COLUMN pipeline_stage.stage_order IS '실행·표시 순서. v1.5 에서 smallint→integer (순번 20개 중 유일한 예외였다)';
COMMENT ON COLUMN pipeline_stage.status IS '`queued` `running` `succeeded` `failed` `skipped`';
COMMENT ON COLUMN pipeline_stage.fatal IS '이 단계 단독 실패가 run 전체를 실패시키는지. 기본값 없이 stage 정의가 결정한다';
COMMENT ON COLUMN pipeline_stage.attempt_count IS '생성된 attempt 수. 상세는 pipeline_stage_attempt';
COMMENT ON COLUMN pipeline_stage.input_version_json IS '입력 asset·schema 버전';
COMMENT ON COLUMN pipeline_stage.output_version_json IS '성공 산출물 버전. 실패 시 NULL';
COMMENT ON COLUMN pipeline_stage.error_code IS '최종 오류 코드';
COMMENT ON COLUMN pipeline_stage.error_summary IS '최종 오류 요약';
COMMENT ON COLUMN pipeline_stage.started_at IS '최초 시작 시각';
COMMENT ON COLUMN pipeline_stage.finished_at IS 'terminal 도달 시각';
COMMENT ON COLUMN pipeline_stage.updated_at IS '상태 갱신 시각';
COMMENT ON COLUMN pipeline_stage.created_at IS '행 생성 시각. v1.5 에서 보강 — started_at 만 있어 언제 만들어졌는지 알 수 없었다';

COMMENT ON COLUMN pipeline_stage_attempt.pipeline_stage_attempt_id IS 'PK. stage 의 실제 실행 시도 1회';
COMMENT ON COLUMN pipeline_stage_attempt.pipeline_stage_id IS 'FK pipeline_stage';
COMMENT ON COLUMN pipeline_stage_attempt.attempt_no IS 'stage 안의 시도 번호. UNIQUE(pipeline_stage_id, attempt_no)';
COMMENT ON COLUMN pipeline_stage_attempt.status IS '`running` `succeeded` `failed`. queued 가 없다 — 행은 실제로 시작할 때 만들어진다';
COMMENT ON COLUMN pipeline_stage_attempt.retryable IS '이 실패를 재시도할 수 있는지. error_category 판정 결과';
COMMENT ON COLUMN pipeline_stage_attempt.lease_owner IS '작업을 잡은 워커 ID. @Version 대신 lease 로 동시성을 제어한다(작업이 트랜잭션보다 길다)';
COMMENT ON COLUMN pipeline_stage_attempt.leased_until IS 'lease 만료 시각. 지나면 다른 워커가 회수한다';
COMMENT ON COLUMN pipeline_stage_attempt.heartbeat_at IS '마지막 heartbeat. 워커 생존 확인';
COMMENT ON COLUMN pipeline_stage_attempt.input_version_json IS '이번 시도의 입력 버전';
COMMENT ON COLUMN pipeline_stage_attempt.output_version_json IS '이번 시도의 출력 버전';
COMMENT ON COLUMN pipeline_stage_attempt.error_category IS 'transient/permanent 등 버전 관리되는 분류. retryable 을 결정한다';
COMMENT ON COLUMN pipeline_stage_attempt.error_code IS '안정 오류 코드';
COMMENT ON COLUMN pipeline_stage_attempt.error_summary IS '민감정보를 제거한 요약';
COMMENT ON COLUMN pipeline_stage_attempt.started_at IS '시도 시작 시각';
COMMENT ON COLUMN pipeline_stage_attempt.finished_at IS '종료 시각';
COMMENT ON COLUMN pipeline_stage_attempt.created_at IS '행 생성 시각';
COMMENT ON COLUMN pipeline_stage_attempt.updated_at IS '상태 갱신 시각';

COMMENT ON COLUMN index_generation.generation_no IS 'PK. 인덱스 전체를 다시 빌드한 세대 번호. IDENTITY(서수 의미가 있어 TSID 를 쓰지 않는다). v1.5 에서 index_version 에서 개명';
COMMENT ON COLUMN index_generation.status IS '`building` `ready` `active` `failed` `superseded`. active 는 전역 1건(부분 유니크 인덱스)';
COMMENT ON COLUMN index_generation.source_manifest_hash IS '색인 대상 목록의 SHA-256. 같은 manifest 면 재빌드가 불필요하다';
COMMENT ON COLUMN index_generation.expected_document_count IS '예상 문서 수. indexed 와 다르면 검증 실패';
COMMENT ON COLUMN index_generation.indexed_document_count IS '실제 색인된 문서 수';
COMMENT ON COLUMN index_generation.index_config_json IS 'BM25·dense·embedding·mapping 설정';
COMMENT ON COLUMN index_generation.error_code IS 'build/validation 오류 코드';
COMMENT ON COLUMN index_generation.error_summary IS '오류 요약';
COMMENT ON COLUMN index_generation.created_at IS 'build 시작 시각';
COMMENT ON COLUMN index_generation.ready_at IS '검증 완료 시각';
COMMENT ON COLUMN index_generation.activated_at IS 'active 전환 시각';
COMMENT ON COLUMN index_generation.superseded_at IS '다음 세대로 교체된 시각. 행은 지우지 않는다';
COMMENT ON COLUMN index_generation.gc_completed_at IS '색인 파일 물리 정리 완료. DB 행 삭제가 아니다';
COMMENT ON COLUMN index_generation.updated_at IS '상태 갱신 시각';

COMMENT ON COLUMN scene.scene_id IS 'PK. 영상 안의 시간 구간 1개. 검색 결과의 단위';
COMMENT ON COLUMN scene.clip_id IS 'FK clip. 어느 영상의 장면인지';
COMMENT ON COLUMN scene.pipeline_run_id IS 'FK pipeline_run. 이 장면을 만든 분석 실행';
COMMENT ON COLUMN scene.processing_no IS 'run 의 처리 순번 사본. FRD 가 FK 로 표시하지 않아 복합 FK 를 만들지 않았다 — run 과의 교차 정합성은 DB 가 강제하지 않는다';
COMMENT ON COLUMN scene.scene_index IS 'run 안의 순번. UNIQUE(pipeline_run_id, scene_index)';
COMMENT ON COLUMN scene.start_time_ms IS '구간 시작(포함). clip 기준 밀리초';
COMMENT ON COLUMN scene.end_time_ms IS '구간 종료(미포함). 경계 규칙이 반열림이다';
COMMENT ON COLUMN scene.thumbnail_asset_id IS 'FK media_asset. 대표 썸네일';
COMMENT ON COLUMN scene.caption IS '화면 관찰 중심 caption. 추론·해석이 아니라 보이는 것만 기술';
COMMENT ON COLUMN scene.scene_type IS 'Gate B 닫힌 어휘 또는 `unknown`. shot_type 과 같은 성격이라 v1.5 에서 폭을 varchar(32)로 통일';
COMMENT ON COLUMN scene.shot_type IS '`anchor` `field_interview` `b_roll` `unknown`';
COMMENT ON COLUMN scene.season IS 'Gate B soft field. 확신 없으면 NULL';
COMMENT ON COLUMN scene.weather IS 'Gate B soft field. 확신 없으면 NULL';
COMMENT ON COLUMN scene.crowd_density IS 'Gate B soft field. 확신 없으면 NULL';
COMMENT ON COLUMN scene.created_at IS '생성 시각. 불변 테이블이라 updated_at 을 두지 않았다';

COMMENT ON COLUMN frame_asset.frame_asset_id IS 'PK. scene 에서 뽑은 프레임 1장';
COMMENT ON COLUMN frame_asset.scene_id IS 'FK scene';
COMMENT ON COLUMN frame_asset.timestamp_ms IS 'clip 기준 프레임 시각';
COMMENT ON COLUMN frame_asset.asset_id IS 'FK media_asset. 실제 이미지 파일';
COMMENT ON COLUMN frame_asset.role IS '`keyframe` 대표 / `thumbnail` 목록용 / `evidence` 근거 제시용';
COMMENT ON COLUMN frame_asset.extraction_version IS '추출 알고리즘·설정 버전 문자열';
COMMENT ON COLUMN frame_asset.created_at IS '생성 시각';

COMMENT ON COLUMN transcript_segment.transcript_segment_id IS 'PK. 자막·발화 구간 1개';
COMMENT ON COLUMN transcript_segment.clip_id IS 'FK clip';
COMMENT ON COLUMN transcript_segment.pipeline_run_id IS 'FK pipeline_run. 이 구간을 만든 실행';
COMMENT ON COLUMN transcript_segment.processing_no IS 'run 처리 순번 사본. scene 과 같은 이유로 복합 FK 아님';
COMMENT ON COLUMN transcript_segment.segment_index IS 'run 안의 순번. UNIQUE(pipeline_run_id, segment_index)';
COMMENT ON COLUMN transcript_segment.start_time_ms IS '구간 시작';
COMMENT ON COLUMN transcript_segment.end_time_ms IS '구간 종료';
COMMENT ON COLUMN transcript_segment.verbatim_text IS '원문 발화·자막 그대로. 정규화하지 않는다';
COMMENT ON COLUMN transcript_segment.source IS '`provided` 제공 대본 / `cc` 방송 자막 / `asr` 음성인식. clip.transcript_source 는 이 값의 요약';
COMMENT ON COLUMN transcript_segment.source_asset_id IS 'FK media_asset. 제공 transcript 파일 또는 원본 video. asr 이면 NULL 일 수 있다';
COMMENT ON COLUMN transcript_segment.source_locator_json IS 'cue 번호·track·원본 위치. 근거 추적용';
COMMENT ON COLUMN transcript_segment.confidence IS '0.0000~1.0000. numeric 이다 — guard 임계값 판정과 감사 재현이 필요해 real 로 바꾸지 않았다';
COMMENT ON COLUMN transcript_segment.model_version IS 'ASR 모델 버전. cc/provided 면 NULL';
COMMENT ON COLUMN transcript_segment.created_at IS '생성 시각';

COMMENT ON COLUMN scene_transcript_segment.scene_id IS 'PK+FK scene. scene 과 segment 를 잇는 연결 테이블';
COMMENT ON COLUMN scene_transcript_segment.transcript_segment_id IS 'PK+FK transcript_segment';
COMMENT ON COLUMN scene_transcript_segment.clip_id IS 'FK clip. 양쪽이 같은 clip 소속임을 나타내는 비정규화 사본';
COMMENT ON COLUMN scene_transcript_segment.pipeline_run_id IS 'FK pipeline_run. 같은 run 소속임을 나타내는 사본';
COMMENT ON COLUMN scene_transcript_segment.processing_no IS '같은 처리 순번 사본';
COMMENT ON COLUMN scene_transcript_segment.overlap_ms IS '두 구간이 실제로 겹친 길이. 매칭 강도 판단에 쓴다';
COMMENT ON COLUMN scene_transcript_segment.created_at IS '매핑 시각';

COMMENT ON COLUMN ocr_observation.ocr_observation_id IS 'PK. 프레임에서 읽어낸 글자 1건';
COMMENT ON COLUMN ocr_observation.scene_id IS 'FK scene';
COMMENT ON COLUMN ocr_observation.frame_asset_id IS 'FK frame_asset. 어느 프레임에서 읽었는지';
COMMENT ON COLUMN ocr_observation.verbatim_text IS 'OCR 원문 그대로';
COMMENT ON COLUMN ocr_observation.normalized_text IS '검색용 정규화 문자열. 원문과 분리해 둔다';
COMMENT ON COLUMN ocr_observation.confidence IS '0.0000~1.0000. NOT NULL';
COMMENT ON COLUMN ocr_observation.bounding_box_json IS '좌표와 coordinate schema. 좌표계가 가변이라 정규화하지 않고 JSONB 로 둔다';
COMMENT ON COLUMN ocr_observation.verification_status IS '`verified` `unverified` `rejected`. unverified 는 guard 가 감점한다';
COMMENT ON COLUMN ocr_observation.producer_version_json IS 'OCR model/schema/preprocess 버전';
COMMENT ON COLUMN ocr_observation.created_at IS '생성 시각';
COMMENT ON COLUMN ocr_observation.updated_at IS 'verification_status 가 바뀔 수 있어 필요하다';

COMMENT ON COLUMN tag.tag_id IS 'PK. 공통 태그 사전 항목 1개';
COMMENT ON COLUMN tag.tag_type IS '`person` 인물 / `organization` 기관 / `location` 장소 / `facility` 시설 / `loose` 느슨한 키워드';
COMMENT ON COLUMN tag.canonical_value IS '검색용 정규화값(예: 서울시청). UNIQUE(tag_type, canonical_value, normalization_version). btree 항목 2704바이트 상한 때문에 varchar(255)';
COMMENT ON COLUMN tag.display_value IS 'UI 표시값(예: 서울특별시청)';
COMMENT ON COLUMN tag.normalization_version IS '정규화 규칙 버전. 규칙이 바뀌면 같은 값이라도 별도 행이 된다';
COMMENT ON COLUMN tag.created_at IS '생성 시각';

COMMENT ON COLUMN scene_tag.scene_tag_id IS 'PK. scene 과 tag 를 잇는 링크의 안정 ID. field_evidence 가 이 링크를 근거 대상으로 참조한다';
COMMENT ON COLUMN scene_tag.scene_id IS 'FK scene';
COMMENT ON COLUMN scene_tag.tag_id IS 'FK tag';
COMMENT ON COLUMN scene_tag.created_at IS '연결 시각';

COMMENT ON COLUMN clip_tag.clip_tag_id IS 'PK. clip 과 tag 를 잇는 링크의 안정 ID';
COMMENT ON COLUMN clip_tag.clip_id IS 'FK clip';
COMMENT ON COLUMN clip_tag.tag_id IS 'FK tag';
COMMENT ON COLUMN clip_tag.created_at IS '연결 시각';

COMMENT ON COLUMN field_evidence.evidence_id IS 'PK. 어떤 값이 왜 그렇게 정해졌는지에 대한 근거 1건';
COMMENT ON COLUMN field_evidence.target_type IS '`clip` `scene` `scene_tag` `clip_tag`. 아래 4개 FK 중 이 값과 일치하는 1개만 채워진다(CHECK 로 강제)';
COMMENT ON COLUMN field_evidence.clip_id IS 'FK clip. target_type=''clip'' 일 때만';
COMMENT ON COLUMN field_evidence.scene_id IS 'FK scene. target_type=''scene'' 일 때만';
COMMENT ON COLUMN field_evidence.scene_tag_id IS 'FK scene_tag. target_type=''scene_tag'' 일 때만';
COMMENT ON COLUMN field_evidence.clip_tag_id IS 'FK clip_tag. target_type=''clip_tag'' 일 때만';
COMMENT ON COLUMN field_evidence.field_name IS '근거가 뒷받침하는 필드명. `incident_name`, date, caption, tag field 등';
COMMENT ON COLUMN field_evidence.field_value_json IS 'typed value 와 value schema';
COMMENT ON COLUMN field_evidence.source IS '`user_input` `original_metadata` `cc` `ocr` `asr` `vlm` `rule`. 사람 입력과 자동 추출을 구분한다';
COMMENT ON COLUMN field_evidence.confidence IS '0.0000~1.0000. user_input 이면 NULL 일 수 있다';
COMMENT ON COLUMN field_evidence.verification_status IS '`verified` `unverified` `rejected`';
COMMENT ON COLUMN field_evidence.evidence_text IS '짧은 근거 발췌. 원문 전체가 아니다';
COMMENT ON COLUMN field_evidence.evidence_asset_id IS 'FK media_asset. 근거가 되는 파일';
COMMENT ON COLUMN field_evidence.source_frame_asset_id IS 'FK frame_asset. VLM·프레임 근거의 위치';
COMMENT ON COLUMN field_evidence.source_ocr_observation_id IS 'FK ocr_observation. OCR 원 관측';
COMMENT ON COLUMN field_evidence.source_transcript_segment_id IS 'FK transcript_segment. CC·ASR 원 구간';
COMMENT ON COLUMN field_evidence.source_locator_json IS 'metadata key·rule ID 등 위 FK 로 표현되지 않는 위치';
COMMENT ON COLUMN field_evidence.pipeline_run_id IS 'FK pipeline_run. 자동 추출을 수행한 run. user_input 이면 NULL';
COMMENT ON COLUMN field_evidence.producer_version_json IS 'producer/model/prompt/schema/normalization 버전';
COMMENT ON COLUMN field_evidence.created_at IS '생성 시각';
COMMENT ON COLUMN field_evidence.updated_at IS 'verification_status 가 바뀔 수 있어 필요하다';

COMMENT ON COLUMN search_index_state.scene_id IS 'PK+FK scene. (scene, 세대) 조합마다 색인 상태 1행';
COMMENT ON COLUMN search_index_state.index_generation_no IS 'PK+FK index_generation. v1.5 에서 index_version 에서 개명';
COMMENT ON COLUMN search_index_state.status IS '`queued` `indexing` `searchable` `failed` `superseded`. 진입 상태만 queued 로 통일하고 진행 상태는 도메인어(indexing)를 유지했다';
COMMENT ON COLUMN search_index_state.attempt_count IS '색인 시도 수';
COMMENT ON COLUMN search_index_state.error_code IS '실패 코드';
COMMENT ON COLUMN search_index_state.searchable_at IS '검색 가능해진 시각';
COMMENT ON COLUMN search_index_state.superseded_at IS '다음 세대로 교체된 시각. clip 논리 삭제 시에도 이 상태로 내린다';
COMMENT ON COLUMN search_index_state.updated_at IS '상태 갱신 시각';
COMMENT ON COLUMN search_index_state.created_at IS '행 생성 시각. v1.5 에서 보강';

COMMENT ON COLUMN index_outbox.outbox_id IS 'PK. 색인 반영 작업 1건. outbox 패턴으로 DB 커밋과 외부 색인을 분리한다';
COMMENT ON COLUMN index_outbox.event_key IS '워커 멱등 키. UNIQUE. 같은 이벤트가 두 번 처리되지 않게 한다';
COMMENT ON COLUMN index_outbox.event_type IS '`upsert_scene` `supersede_scene` `validate_generation` `activate_generation`';
COMMENT ON COLUMN index_outbox.index_generation_no IS 'FK index_generation. 대상 세대';
COMMENT ON COLUMN index_outbox.scene_id IS 'FK scene. scene 단위 이벤트에만. generation 단위 이벤트면 NULL';
COMMENT ON COLUMN index_outbox.status IS '`queued` `processing` `succeeded` `failed`';
COMMENT ON COLUMN index_outbox.lease_owner IS '작업을 잡은 워커 ID';
COMMENT ON COLUMN index_outbox.leased_until IS 'lease 만료. 지나면 회수된다';
COMMENT ON COLUMN index_outbox.attempt_count IS '시도 수';
COMMENT ON COLUMN index_outbox.next_attempt_at IS '다음 재시도 예정 시각. 폴링 부분 인덱스의 키';
COMMENT ON COLUMN index_outbox.last_error IS '민감정보를 제거한 마지막 오류';
COMMENT ON COLUMN index_outbox.created_at IS '생성 시각';
COMMENT ON COLUMN index_outbox.processed_at IS '성공 또는 최종 실패 시각';
COMMENT ON COLUMN index_outbox.updated_at IS '상태 갱신 시각';

COMMENT ON COLUMN search_configuration.config_no IS 'PK. 검색 파라미터 구성의 개정 번호. IDENTITY. v1.5 에서 search_version 에서 개명';
COMMENT ON COLUMN search_configuration.index_generation_no IS 'FK index_generation. 이 구성이 물린 색인 세대. UNIQUE(config_no, index_generation_no) 가 search_execution 의 복합 FK 대상이다';
COMMENT ON COLUMN search_configuration.normalization_version IS 'query 정규화 규칙 버전 문자열';
COMMENT ON COLUMN search_configuration.resolution_schema_version IS 'resolution JSON Schema 버전 문자열';
COMMENT ON COLUMN search_configuration.guard_policy_version IS 'false-hit guard 정책 버전 문자열';
COMMENT ON COLUMN search_configuration.parameters_json IS 'candidate 수·RRF·boost·timeout 등';
COMMENT ON COLUMN search_configuration.created_at IS '생성 시각';

COMMENT ON COLUMN search_session.search_session_id IS 'PK. 편집기자의 검색 1건. 재실행(replay)까지 묶는 단위';
COMMENT ON COLUMN search_session.editor_id IS 'FK member. 검색한 편집기자. 서버가 현재 세션에서 채우며 클라이언트 값을 믿지 않는다';
COMMENT ON COLUMN search_session.original_query IS '사용자가 입력한 원문 query. 그대로 보존한다';
COMMENT ON COLUMN search_session.canonical_query IS '정규화된 query';
COMMENT ON COLUMN search_session.explicit_filters_json IS '사용자가 지정한 filter 원본';
COMMENT ON COLUMN search_session.canonical_filters_json IS '정규화된 filter';
COMMENT ON COLUMN search_session.canonical_scope_payload IS 'query+filter 를 결정적으로 직렬화한 원문. fingerprint 의 입력';
COMMENT ON COLUMN search_session.query_fingerprint IS 'scope 의 SHA-256. exact override 조회의 키라 인덱스가 붙는다';
COMMENT ON COLUMN search_session.normalization_version IS '이 정규화에 쓴 규칙 버전';
COMMENT ON COLUMN search_session.created_at IS '검색 시각';

COMMENT ON COLUMN search_execution.search_execution_id IS 'PK. 검색 실행 1회. 같은 session 이라도 replay 하면 새 행이 생긴다';
COMMENT ON COLUMN search_execution.search_session_id IS 'FK search_session';
COMMENT ON COLUMN search_execution.request_id IS 'FK api_idempotency_request. 이 실행을 만든 멱등 요청';
COMMENT ON COLUMN search_execution.requested_by_id IS 'FK member. editor 본인 또는 replay 하는 검수자';
COMMENT ON COLUMN search_execution.replay_of_inquiry_id IS 'FK feedback_inquiry. reviewer_replay 일 때만 채워진다. execution_type 과 짝이 맞아야 한다(CHECK). search_execution→feedback_inquiry→search_result→search_execution 3단 순환이라 이 FK 는 ALTER 로 분리해 만든다';
COMMENT ON COLUMN search_execution.execution_type IS '`initial` 편집기자 최초 검색 / `reviewer_replay` 검수자 재현';
COMMENT ON COLUMN search_execution.status IS '`running` `succeeded` `degraded` `failed`. degraded 는 일부 채널 실패 후 fallback 으로 반환한 경우';
COMMENT ON COLUMN search_execution.resolver_status IS '최초 `not_started`, 이후 resolver/patch/fallback 결과 코드';
COMMENT ON COLUMN search_execution.fallback_used IS 'raw BM25 fallback 을 썼는지';
COMMENT ON COLUMN search_execution.degraded_reasons_json IS '누락된 channel 과 사유. 기본값이 v1.0 에서 jsonb 캐스팅 불가능한 빈 문자열이던 것을 ''[]'' 로 교정';
COMMENT ON COLUMN search_execution.error_code IS 'terminal 실패 코드';
COMMENT ON COLUMN search_execution.search_configuration_no IS 'FK search_configuration. 실행 시점의 검색 구성. index_generation_no 와 함께 복합 FK 를 이룬다';
COMMENT ON COLUMN search_execution.index_generation_no IS '실행 시점의 색인 세대. 위와 함께 (config_no, index_generation_no) 로 search_configuration 을 참조';
COMMENT ON COLUMN search_execution.lease_owner IS '실행 중인 워커·요청 소유자';
COMMENT ON COLUMN search_execution.leased_until IS 'stale 실행 판단 기준';
COMMENT ON COLUMN search_execution.heartbeat_at IS '마지막 heartbeat';
COMMENT ON COLUMN search_execution.resolver_latency_ms IS 'resolver 소요 시간(ms). 성능 관측용';
COMMENT ON COLUMN search_execution.started_at IS '실행 시작 시각';
COMMENT ON COLUMN search_execution.finished_at IS 'terminal 시각';
COMMENT ON COLUMN search_execution.created_at IS '행 생성 시각. v1.5 에서 보강';
COMMENT ON COLUMN search_execution.updated_at IS '상태 갱신 시각';

COMMENT ON COLUMN search_result_snapshot.result_snapshot_id IS 'PK. 실행 1회의 후보·판정 전체 기록. 실행당 0..1(UNIQUE)';
COMMENT ON COLUMN search_result_snapshot.search_execution_id IS 'FK search_execution. 아래 두 버전 컬럼과 함께 3컬럼 복합 FK 를 이룬다';
COMMENT ON COLUMN search_result_snapshot.pre_guard_results_json IS 'guard 적용 전 후보·rank·score';
COMMENT ON COLUMN search_result_snapshot.guard_decisions_json IS 'anchor 별 판정과 근거';
COMMENT ON COLUMN search_result_snapshot.applied_excludes_json IS '적용된 exclude 규칙의 비정규화 기록';
COMMENT ON COLUMN search_result_snapshot.final_results_json IS '최종 Top 10 스냅샷. search_result 행과 값이 겹치지만 살아 있는 scene 에 결합되지 않은 불변 사본이라 감사에 쓴다';
COMMENT ON COLUMN search_result_snapshot.search_configuration_no IS '실행 당시 검색 구성 번호';
COMMENT ON COLUMN search_result_snapshot.index_generation_no IS '실행 당시 색인 세대. 복합 FK 로 ''스냅샷의 버전 = 실행 당시 버전''을 DB 가 보장한다';
COMMENT ON COLUMN search_result_snapshot.created_at IS '저장 시각';

COMMENT ON COLUMN search_result.search_result_id IS 'PK. 최종 결과 1건(장면 1개). 문의(feedback_inquiry)의 출발점';
COMMENT ON COLUMN search_result.search_execution_id IS 'FK search_execution';
COMMENT ON COLUMN search_result.scene_id IS 'FK scene. 결과로 제시된 장면';
COMMENT ON COLUMN search_result.scene_processing_no IS '당시 scene 의 처리 순번 사본. FRD 가 FK 로 표시하지 않아 복합 FK 를 만들지 않았다';
COMMENT ON COLUMN search_result.result_rank IS '최종 순위. UNIQUE(search_execution_id, result_rank)';
COMMENT ON COLUMN search_result.score_breakdown_json IS 'channel 별 점수·RRF·boost';
COMMENT ON COLUMN search_result.match_snapshot_json IS 'matched field/value/source/evidence';
COMMENT ON COLUMN search_result.guard_snapshot_json IS 'match·unverified·conflict 판정과 사유';
COMMENT ON COLUMN search_result.created_at IS '저장 시각';

COMMENT ON COLUMN feedback_inquiry.inquiry_id IS 'PK. 편집기자가 ''이 결과 이상해요''라고 알린 기록. 교정값 자체가 아니다';
COMMENT ON COLUMN feedback_inquiry.search_result_id IS 'FK search_result. 문제가 된 저장된 결과. 해당 실행의 결과가 아닌 scene 은 거부한다(FR-FBK-010)';
COMMENT ON COLUMN feedback_inquiry.editor_id IS 'FK member. 문의한 편집기자';
COMMENT ON COLUMN feedback_inquiry.context_snapshot_json IS '당시 query·resolution·rank·guard·version. 생성 후 수정하지 않는다(FR-FBK-011)';
COMMENT ON COLUMN feedback_inquiry.context_schema_version IS 'context JSON Schema 버전';
COMMENT ON COLUMN feedback_inquiry.comment IS '편집기자의 선택 서술. inquiry_resolution.reviewer_reason 과 다른 값이다';
COMMENT ON COLUMN feedback_inquiry.idempotency_key IS '같은 actor·key 의 반복 제출은 같은 inquiry 를 반환한다(FR-FBK-012)';
COMMENT ON COLUMN feedback_inquiry.status IS '`pending` `reviewing` `resolved` `dismissed` `deferred`';
COMMENT ON COLUMN feedback_inquiry.reviewer_id IS 'FK member. claim 한 검수자. pending 이면 NULL';
COMMENT ON COLUMN feedback_inquiry.row_version IS 'JPA @Version. 검수자 claim 경합에서 lost update 를 막는다';
COMMENT ON COLUMN feedback_inquiry.created_at IS '접수 시각';
COMMENT ON COLUMN feedback_inquiry.review_started_at IS 'claim 시각';
COMMENT ON COLUMN feedback_inquiry.closed_at IS 'terminal 시각';
COMMENT ON COLUMN feedback_inquiry.updated_at IS '상태 갱신 시각';

COMMENT ON COLUMN pinned_override.override_id IS 'PK. 검색 품질 보정 규칙 1건. 문의에서만 생성된다';
COMMENT ON COLUMN pinned_override.query_fingerprint IS 'exact scope 조회 가속용 해시. lifecycle_status=''active'' 부분 인덱스가 붙는다';
COMMENT ON COLUMN pinned_override.canonical_query IS '정규화 query';
COMMENT ON COLUMN pinned_override.canonical_filters_json IS '정규화 filter';
COMMENT ON COLUMN pinned_override.canonical_scope_payload IS '충돌 없이 scope 를 식별하는 전체 원문';
COMMENT ON COLUMN pinned_override.normalization_version IS 'scope 정규화 규칙 버전';
COMMENT ON COLUMN pinned_override.action IS '`resolution_patch` 검색어 해석 교체 / `exclude_scene` 장면 제외. 어느 컬럼이 채워지는지를 이 값이 결정한다(CHECK)';
COMMENT ON COLUMN pinned_override.target_scene_id IS 'FK scene. exclude_scene 일 때만. target_scene_processing_no 와 동시에 NULL 이거나 동시에 채워져야 한다(CHECK)';
COMMENT ON COLUMN pinned_override.target_scene_processing_no IS '제외 대상 scene 의 처리 순번';
COMMENT ON COLUMN pinned_override.resolution_value_json IS 'resolution_patch 일 때의 전체 대체 resolution';
COMMENT ON COLUMN pinned_override.resolution_schema_version IS 'patch schema 버전';
COMMENT ON COLUMN pinned_override.source_inquiry_id IS 'FK feedback_inquiry. 이 규칙을 만든 근거 문의. P0 에서 필수다';
COMMENT ON COLUMN pinned_override.reason IS '검수 사유. 필수';
COMMENT ON COLUMN pinned_override.reviewer_id IS 'FK member. 규칙을 만든 검수자';
COMMENT ON COLUMN pinned_override.revision_no IS 'supersedes 체인 안에서 몇 번째 개정인지. v1.5 에서 version 에서 개명(정수 서수라 _no)';
COMMENT ON COLUMN pinned_override.row_version IS 'JPA @Version. revoke 경합에서 lost update 를 막는다';
COMMENT ON COLUMN pinned_override.supersedes_override_id IS 'FK 자기 참조. 같은 series 의 직전 개정';
COMMENT ON COLUMN pinned_override.lifecycle_status IS '`pending_verification` `active` `superseded` `revoked` `stale`. revoked 가 논리 삭제 역할을 하므로 deleted_at 이 없다';
COMMENT ON COLUMN pinned_override.status_reason IS '비활성 사유';
COMMENT ON COLUMN pinned_override.created_at IS 'candidate 생성 시각';
COMMENT ON COLUMN pinned_override.verified_at IS 'replay 로 검증된 시각';
COMMENT ON COLUMN pinned_override.activated_at IS 'active 전환 시각';
COMMENT ON COLUMN pinned_override.deactivated_at IS '비활성 전환 시각';
COMMENT ON COLUMN pinned_override.updated_at IS '상태 갱신 시각';

COMMENT ON COLUMN search_execution_applied_override.search_execution_id IS 'PK+FK search_execution. 어떤 실행에 어떤 규칙이 실제로 적용됐는지 기록하는 N:M 링크';
COMMENT ON COLUMN search_execution_applied_override.override_id IS 'PK+FK pinned_override';
COMMENT ON COLUMN search_execution_applied_override.application_stage IS '`resolution` 해석 단계 / `post_guard` guard 이후 단계';
COMMENT ON COLUMN search_execution_applied_override.created_at IS '적용 기록 시각';

COMMENT ON COLUMN query_resolution_snapshot.resolution_snapshot_id IS 'PK. 검색어 해석 결과 기록. 실행당 0..1(UNIQUE)';
COMMENT ON COLUMN query_resolution_snapshot.search_execution_id IS 'FK search_execution 직접 참조. v1.0 은 링크 테이블만 경유해 override 가 적용된 실행에서만 스냅샷이 존재할 수 있었다 — v1.5 교정';
COMMENT ON COLUMN query_resolution_snapshot.resolution_source IS '`resolver` 모델 해석 / `resolution_patch` 검수자 교정 / `raw_fallback` 해석 실패 후 원문 사용';
COMMENT ON COLUMN query_resolution_snapshot.resolution_value_json IS '검증을 통과한 전체 resolution';
COMMENT ON COLUMN query_resolution_snapshot.explicit_anchor_validation_json IS 'span·anchor 검증 결과';
COMMENT ON COLUMN query_resolution_snapshot.model_version IS 'resolver 모델 버전. raw_fallback 이면 NULL';
COMMENT ON COLUMN query_resolution_snapshot.prompt_version IS 'prompt 버전';
COMMENT ON COLUMN query_resolution_snapshot.resolution_schema_version IS 'resolution JSON Schema 버전';
COMMENT ON COLUMN query_resolution_snapshot.normalization_version IS '정규화 규칙 버전';
COMMENT ON COLUMN query_resolution_snapshot.applied_override_id IS '적용된 patch. search_execution_id 와 함께 링크 테이블로 복합 FK 를 이룬다(값이 있을 때만)';
COMMENT ON COLUMN query_resolution_snapshot.failure_category IS 'timeout/schema/rate/network 등 해석 실패 분류';
COMMENT ON COLUMN query_resolution_snapshot.created_at IS '저장 시각';

COMMENT ON COLUMN inquiry_resolution.inquiry_id IS 'PK+FK feedback_inquiry. 문의 1건당 최종 조치 0..1';
COMMENT ON COLUMN inquiry_resolution.resolution_type IS '`resolution_patch` `exclude_scene` `no_action` `deferred_p1`. 앞 둘이면 override_id 가 필수다';
COMMENT ON COLUMN inquiry_resolution.override_id IS 'FK pinned_override. 활성화된 최종 규칙';
COMMENT ON COLUMN inquiry_resolution.verification_execution_id IS 'FK search_execution. 고쳐진 것을 확인한 검수자 replay';
COMMENT ON COLUMN inquiry_resolution.deferred_target_json IS 'P1 로 미룬 경우의 target·evidence·설명';
COMMENT ON COLUMN inquiry_resolution.reviewer_reason IS '검수자가 쓴 필수 사유. feedback_inquiry.comment(편집기자 서술)와 다른 값이다';
COMMENT ON COLUMN inquiry_resolution.created_at IS 'terminal 저장 시각';

COMMENT ON COLUMN override_lifecycle_history.override_lifecycle_history_id IS 'PK. override 상태 전환 이력 1건. append-only 라 updated_at 이 없다';
COMMENT ON COLUMN override_lifecycle_history.override_id IS 'FK pinned_override';
COMMENT ON COLUMN override_lifecycle_history.from_status IS '전환 전 상태. 최초 생성이면 NULL';
COMMENT ON COLUMN override_lifecycle_history.to_status IS '전환 후 상태';
COMMENT ON COLUMN override_lifecycle_history.changed_by_id IS 'FK member. 전환 주체. NULL 이면 시스템 전환이다. v1.5 에서 actor_id 를 대체하며 member.role 의 가짜 system 행을 없앴다';
COMMENT ON COLUMN override_lifecycle_history.changed_by_kind IS '`user` `system`. CHECK 로 changed_by_id 의 NULL 여부와 짝이 맞도록 강제한다';
COMMENT ON COLUMN override_lifecycle_history.reason IS '전환 사유. 필수';
COMMENT ON COLUMN override_lifecycle_history.created_at IS '전환 시각';

COMMENT ON COLUMN inquiry_status_history.inquiry_status_history_id IS 'PK. 문의 상태 전환 이력 1건. append-only';
COMMENT ON COLUMN inquiry_status_history.inquiry_id IS 'FK feedback_inquiry';
COMMENT ON COLUMN inquiry_status_history.from_status IS '전환 전 상태. 최초 생성이면 NULL';
COMMENT ON COLUMN inquiry_status_history.to_status IS '전환 후 상태';
COMMENT ON COLUMN inquiry_status_history.changed_by_id IS 'FK member. 전환 주체. NULL 이면 시스템 전환';
COMMENT ON COLUMN inquiry_status_history.changed_by_kind IS '`user` `system`. CHECK 로 changed_by_id 와 짝을 강제';
COMMENT ON COLUMN inquiry_status_history.reason IS '생성·claim·terminal 사유. 필수';
COMMENT ON COLUMN inquiry_status_history.created_at IS '전환 시각';

COMMENT ON COLUMN external_provider_profile.profile_no IS 'PK. 외부 AI provider 설정의 개정 번호. IDENTITY. v1.5 에서 provider_profile_version 에서 개명';
COMMENT ON COLUMN external_provider_profile.provider_name IS 'provider 제품명';
COMMENT ON COLUMN external_provider_profile.account_alias IS 'secret 이 아닌 계정 별칭. 실제 자격증명은 저장하지 않는다';
COMMENT ON COLUMN external_provider_profile.region IS '리전';
COMMENT ON COLUMN external_provider_profile.profile_schema_version IS '이 profile JSON 들의 schema 버전';
COMMENT ON COLUMN external_provider_profile.endpoint_allowlist_json IS 'HTTPS scheme·host·port 허용 목록. 여기 없는 곳으로는 나가지 않는다';
COMMENT ON COLUMN external_provider_profile.component_model_map_json IS 'component 별 사용할 model';
COMMENT ON COLUMN external_provider_profile.payload_allowlist_json IS '보낼 수 있는 category 와 max_bytes';
COMMENT ON COLUMN external_provider_profile.rights_evidence_refs_json IS 'dataset·provider 권리 증빙 reference';
COMMENT ON COLUMN external_provider_profile.retention_training_deletion_summary_json IS '보관·학습·삭제 승인 요약';
COMMENT ON COLUMN external_provider_profile.evidence_reviewed_at IS '증빙 검토 시각';
COMMENT ON COLUMN external_provider_profile.evidence_valid_until IS '증빙 만료. 지나면 policy 에서 선택할 수 없어야 한다';
COMMENT ON COLUMN external_provider_profile.active IS 'policy 가 고를 수 있는 후보인지. scope 개념이 없어 동시에 여러 개가 active 일 수 있다';
COMMENT ON COLUMN external_provider_profile.created_at IS '생성 시각';
COMMENT ON COLUMN external_provider_profile.updated_at IS 'active 가 바뀔 수 있어 필요하다';

COMMENT ON COLUMN deployment_external_policy.policy_no IS 'PK. 배포 환경별 외부 전송 정책의 개정 번호. IDENTITY. v1.5 에서 deployment_policy_version 에서 개명';
COMMENT ON COLUMN deployment_external_policy.deployment_scope IS 'P0 배포 식별자. 같은 scope 에 active 정책은 1건(부분 유니크 인덱스)';
COMMENT ON COLUMN deployment_external_policy.provider_profile_no IS 'FK external_provider_profile. 이 정책이 고정한 provider';
COMMENT ON COLUMN deployment_external_policy.query_external_processing_allowed IS '`yes` `no`. 검색어를 외부로 보낼 수 있는지';
COMMENT ON COLUMN deployment_external_policy.approved_by_id IS 'FK member. 승인한 검수자. v1.5 에서 approved_by 에 _id 를 붙였다';
COMMENT ON COLUMN deployment_external_policy.approved_at IS '승인 시각';
COMMENT ON COLUMN deployment_external_policy.active IS '현재 scope 에 적용 중인지';
COMMENT ON COLUMN deployment_external_policy.created_at IS '생성 시각';
COMMENT ON COLUMN deployment_external_policy.updated_at IS 'active 가 바뀔 수 있어 필요하다';

COMMENT ON COLUMN external_call_audit.external_call_id IS 'PK. 외부 AI 로 나간 호출 1건. 권리·컴플라이언스 증빙용이라 지우지 않는다';
COMMENT ON COLUMN external_call_audit.pipeline_run_id IS 'FK pipeline_run. vlm/ocr/asr 호출의 부모. search_execution_id 와 정확히 하나만 채워진다(CHECK)';
COMMENT ON COLUMN external_call_audit.search_execution_id IS 'FK search_execution. resolver 호출의 부모';
COMMENT ON COLUMN external_call_audit.provider_profile_no IS 'FK external_provider_profile. 실제로 쓴 provider 설정';
COMMENT ON COLUMN external_call_audit.deployment_policy_no IS 'FK deployment_external_policy. 실제로 적용된 정책';
COMMENT ON COLUMN external_call_audit.component IS '`resolver` `vlm` `ocr` `asr`. 앞 하나는 search, 뒤 셋은 pipeline 계열이다';
COMMENT ON COLUMN external_call_audit.attempt_no IS '부모·component 안의 시도 번호';
COMMENT ON COLUMN external_call_audit.payload_categories_json IS '실제 content 가 아니라 보낸 category 목록만. 원문은 저장하지 않는다';
COMMENT ON COLUMN external_call_audit.payload_size_bytes IS 'outbound 크기';
COMMENT ON COLUMN external_call_audit.license_decision IS 'Gate D 판정 코드. 보낼 수 있다고 판단한 근거';
COMMENT ON COLUMN external_call_audit.status IS 'Gate D 호출 상태 어휘';
COMMENT ON COLUMN external_call_audit.provider_request_id IS 'content 없는 provider 상관관계 ID. 장애 시 provider 와 대조용';
COMMENT ON COLUMN external_call_audit.error_code IS '안정 오류 코드';
COMMENT ON COLUMN external_call_audit.started_at IS '판정·호출 시작 시각';
COMMENT ON COLUMN external_call_audit.finished_at IS '종료 시각';
COMMENT ON COLUMN external_call_audit.created_at IS '행 생성 시각. v1.5 에서 보강';
COMMENT ON COLUMN external_call_audit.updated_at IS '상태 갱신 시각';
