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

    public static void insertPipelineRun(JdbcTemplate jdbc, long clipId, long runId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO npick.pipeline_run(pipeline_run_id, clip_id, processing_no, pipeline_version, "
                + "status, stage_states_json, created_at, updated_at) "
                + "VALUES (?, ?, (SELECT COALESCE(MAX(processing_no), 0) + 1 FROM npick.pipeline_run "
                + "WHERE clip_id = ?), 'v1', 'succeeded', '{}'::jsonb, ?, ?) ON CONFLICT DO NOTHING",
                runId, clipId, clipId, now, now);
    }
}
