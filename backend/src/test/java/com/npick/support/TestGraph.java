package com.npick.support;

import java.time.OffsetDateTime;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 교정 상태 지문 테스트(S15P21A501-83·-84)가 공유하는 최소 그래프 픽스처.
 *
 * <p>member → clip → pipeline_run → scene → search_execution(original) → search_result → feedback 순으로 FK 를 만족하는
 * 최소 행을 심는다. clip.active_pipeline_run_id 는 pipeline_run 삽입 후 UPDATE 로 잇는다(순환 FK).
 */
public final class TestGraph {
    private TestGraph() {}

    public static void insertReportedScene(JdbcTemplate jdbc, long memberId, long clipId, long runId, long sceneId,
            long execId, long resultId, long feedbackId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                "INSERT INTO npick.member(member_id, login_id, password_hash, name, role, created_at, updated_at) "
                        + "VALUES (?, ?, 'x', 'tester', 'reviewer', ?, ?) ON CONFLICT DO NOTHING",
                memberId, "m" + memberId, now, now);
        jdbc.update("INSERT INTO npick.clip(clip_id, source_type, storage_key, content_hash, transcript_source, "
                + "registered_by_id, created_at, updated_at) "
                + "VALUES (?, 'broadcast', 'clips/x', ?, 'none', ?, ?, ?) ON CONFLICT DO NOTHING",
                clipId, "hash-" + clipId, memberId, now, now);
        insertPipelineRun(jdbc, clipId, runId);
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id = ? WHERE clip_id = ?", runId, clipId);
        jdbc.update("INSERT INTO npick.scene(scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, "
                + "shot_type, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, 1000, 'b_roll', ?, ?) ON CONFLICT DO NOTHING", sceneId, clipId, runId, now, now);
        jdbc.update("INSERT INTO npick.search_execution(search_execution_id, searched_by_id, query_text, "
                + "normalized_query, explicit_filters_json, normalized_filters_json, query_fingerprint, "
                + "normalization_version, execution_type, status, degraded_reasons_json, applied_excludes_json, "
                + "search_config_json, config_version, created_at, updated_at) "
                + "VALUES (?, ?, '원본질의', '원본질의', '{}'::jsonb, '{}'::jsonb, 'fp', 'norm/v1', 'original', "
                + "'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'search-config/v1:seed', ?, ?) "
                + "ON CONFLICT DO NOTHING",
                execId, memberId, now, now);
        jdbc.update("INSERT INTO npick.search_result(search_result_id, search_execution_id, scene_id, result_rank, "
                + "explain_json) VALUES (?, ?, ?, 1, '{}'::jsonb) ON CONFLICT DO NOTHING", resultId, execId, sceneId);
        jdbc.update("INSERT INTO npick.feedback(feedback_id, search_result_id, created_by_id, status, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'REVIEWING', ?, ?) ON CONFLICT DO NOTHING",
                feedbackId, resultId, memberId, now, now);
    }

    /**
     * {@link #insertReportedScene} 과 같되 장면에 {@code caption}/{@code caption_tokens} 를 채워 실제 검색 파이프라인
     * ({@code interpret}+{@code rank}, S15P21A501-83 Task 3)이 오류 없이 돌 수 있게 한다. BM25 인덱스(pg_search)가 대상으로 삼는
     * 컬럼은 caption_tokens/transcript_tokens 이므로 최소한 그 하나는 채워야 단어 채널 조회가 성립한다.
     */
    public static void insertSearchableReportedScene(JdbcTemplate jdbc, long memberId, long clipId, long runId,
            long sceneId, long execId, long resultId, long feedbackId) {
        insertReportedScene(jdbc, memberId, clipId, runId, sceneId, execId, resultId, feedbackId);
        jdbc.update("UPDATE npick.scene SET caption = '원본질의 장면', caption_tokens = '원본질의 장면' "
                + "WHERE scene_id = ?", sceneId);
    }

    public static void insertPipelineRun(JdbcTemplate jdbc, long clipId, long runId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO npick.pipeline_run(pipeline_run_id, clip_id, processing_no, pipeline_version, "
                + "status, stage_states_json, created_at, updated_at) "
                + "VALUES (?, ?, (SELECT COALESCE(MAX(processing_no), 0) + 1 FROM npick.pipeline_run "
                + "WHERE clip_id = ?), 'v1', 'succeeded', '{}'::jsonb, ?, ?) ON CONFLICT DO NOTHING",
                runId, clipId, clipId, now, now);
    }

    /**
     * 검수자 교정 대기 태그 후보(S15P21A501-83·-160): {@code tag_evidence(source='reviewer_feedback', confirmed=false,
     * source_feedback_id, verification_status='verified')}. tag/tagging 은 tag_evidence 와 별도 PK 공간이므로 evidenceId 를
     * 그대로 재사용해 셋 다 만든다. 확정(-84) 전까지 검색에 반영되지 않는 대기 상태를 심는다.
     *
     * @return 생성한 tagging_id (evidenceId 와 같은 값)
     */
    public static long insertReviewerTagCandidate(
            JdbcTemplate jdbc, long sceneId, long clipId, long feedbackId, long evidenceId) {
        OffsetDateTime now = OffsetDateTime.now();
        long tagId = evidenceId;
        long taggingId = evidenceId;
        jdbc.update("INSERT INTO npick.tag(tag_id, tag_type, match_value, name) VALUES (?, 'person', ?, 'tester') "
                + "ON CONFLICT DO NOTHING", tagId, "person-" + tagId);
        jdbc.update("INSERT INTO npick.tagging(tagging_id, clip_id, scene_id, tag_id, created_at) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", taggingId, clipId, sceneId, tagId, now);
        jdbc.update("INSERT INTO npick.tag_evidence(evidence_id, tagging_id, source, confidence, "
                + "verification_status, source_feedback_id, confirmed, created_at) "
                + "VALUES (?, ?, 'reviewer_feedback', NULL, 'verified', ?, false, ?) ON CONFLICT DO NOTHING",
                evidenceId, taggingId, feedbackId, now);
        return taggingId;
    }

    /** 이미 활성인 patch_parse 규칙(R1). 검증 후보의 교체 대상이 된다. */
    public static void insertActivePatchRule(JdbcTemplate jdbc, long feedbackId, long ruleId) {
        insertPatchRule(jdbc, feedbackId, ruleId, true, null);
    }

    /**
     * 대기 중인 patch_parse 교체 후보(R2 -> R1): {@code active=false, replaces_rule_id=replacesRuleId}
     * ({@code ck_search_rule_replaces_shape}, S15P21A501-81).
     */
    public static void insertPendingPatchRuleReplacing(
            JdbcTemplate jdbc, long feedbackId, long ruleId, long replacesRuleId) {
        insertPatchRule(jdbc, feedbackId, ruleId, false, replacesRuleId);
    }

    /** 교체 대상 없이 대기 중인 patch_parse 신규 후보: {@code active=false, replaces_rule_id=null}. */
    public static void insertPendingPatchRule(JdbcTemplate jdbc, long feedbackId, long ruleId) {
        insertPatchRule(jdbc, feedbackId, ruleId, false, null);
    }

    private static void insertPatchRule(
            JdbcTemplate jdbc, long feedbackId, long ruleId, boolean active, Long replacesRuleId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO npick.search_rule(search_rule_id, query_fingerprint, normalized_query, "
                + "normalized_filters_json, normalization_version, action, source_feedback_id, active, "
                + "replaces_rule_id, condition_json, patch_json, created_at, updated_at) "
                + "VALUES (?, ?, '원본질의', '{}'::jsonb, 'norm/v1', 'patch_parse', ?, ?, ?, '{}'::jsonb, "
                + "'{}'::jsonb, ?, ?) ON CONFLICT DO NOTHING",
                ruleId, "fp-" + ruleId, feedbackId, active, replacesRuleId, now, now);
    }
}
