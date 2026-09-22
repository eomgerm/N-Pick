package com.npick.search.infrastructure.persistence.query;

import jakarta.persistence.EntityManager;

/**
 * 「내 검색 기록」조회 테스트 고정 데이터 (S15P21A501-198).
 *
 * <p>목록·상세 어댑터가 같은 기록을 보게 해 두 판정이 갈리지 않는 것을 확인할 수 있게 한다. -60 저장 구현이 아직 없어 행을 직접 넣는다 — 실제 검색→기록 왕복 확인은 -59/-60 머지 후 별도로
 * 한다.
 */
final class SearchHistoryFixture {

    static final long OWNER = 9001L;
    static final long OTHER = 9003L;

    /** 결과 저장이 온전한 실행이 이 explain_json 을 쓴다. display·match 가 있어야 복원 가능하다. */
    static final String EXPLAIN_COMPLETE = """
            {"score": {"base_score": 0.5},
             "match": {"matched_keywords": ["서울역"],
                       "match_evidence": [{"field": "ocr", "value": "서울역",
                                           "source": "keyframe_ocr",
                                           "verification_status": "verified"}]},
             "guard": {"exclusion_reason": null},
             "display": {"display_name": "예시 뉴스 · 서울역",
                         "scene_description": "대합실 인파",
                         "start_time_ms": 42000, "end_time_ms": 49000,
                         "shot_type": "b_roll", "scene_type": "역사 인파",
                         "broadcast_date": {"value": "2026-09-14", "verification_status": "verified"},
                         "filmed_date": {"value": null, "verification_status": "unknown"}}}""";

    static final String FILTERED = """
            {"returned_count": 2, "shortage_reasons": ["candidate_pool_exhausted"],
             "guard": {"incident_guard_active": false, "verdicts": []}}""";

    private final EntityManager em;

    SearchHistoryFixture(EntityManager em) {
        this.em = em;
    }

    void seed() {
        member(OWNER, "editor-9001");
        member(OTHER, "editor-9003");
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at)"
                + " VALUES (9101, 'broadcast', 'clips/9101/original', repeat('a', 64), 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at)"
                + " VALUES (9201, 9101, 3, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now())");
        for (int i = 1; i <= 5; i++) {
            scene(9300 + i, 40000 + i * 1000, 45000 + i * 1000);
        }

        // 최신순 기대 순서: 9703, 9702(시각 동률 → id DESC), 9701.
        execution(9701, OWNER, "가장 오래된 질의", "succeeded", "original", null, "2026-09-15T01:00:00Z", FILTERED);
        result(9801, 9701, 9301, 1, EXPLAIN_COMPLETE);
        result(9802, 9701, 9302, 2, EXPLAIN_COMPLETE);

        execution(9702, OWNER, "동률 질의 A", "degraded", "original", null, "2026-09-15T03:00:00Z", FILTERED);
        result(9803, 9702, 9303, 1, EXPLAIN_COMPLETE);

        execution(9703, OWNER, "동률 질의 B", "succeeded", "original", null, "2026-09-15T03:00:00Z", FILTERED);
        result(9804, 9703, 9304, 1, EXPLAIN_COMPLETE);

        // 대상 밖 3종. replay 는 ck 제약상 feedback 참조가 필수다.
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status,"
                + " created_at, updated_at) VALUES (9901, 9801, 9001, 'OPEN', now(), now())");
        execution(9704, OWNER, "재검색", "succeeded", "replay", 9901L, "2026-09-15T04:00:00Z", FILTERED);
        execution(9705, OWNER, "진행 중", "running", "original", null, "2026-09-15T04:00:00Z", null);
        execution(9706, OWNER, "실패", "failed", "original", null, "2026-09-15T04:00:00Z", null);

        // 타인 실행 — 소유자 격리 확인용.
        execution(9707, OTHER, "타인 질의", "succeeded", "original", null, "2026-09-15T05:00:00Z", FILTERED);
        result(9805, 9707, 9305, 1, EXPLAIN_COMPLETE);
    }

    private void member(long id, String loginId) {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (" + id + ", '" + loginId + "', 'hash', '편집기자" + id + "', 'editor', now(), now())");
    }

    private void scene(long id, int start, int end) {
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (" + id + ", 9101, 9201, " + start + ", " + end
                + ", 'b_roll', now(), now())");
    }

    private void execution(
            long id,
            long ownerId,
            String queryText,
            String status,
            String executionType,
            Long replayOfFeedbackId,
            String createdAt,
            String filteredJson) {
        exec("INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text,"
                + " normalized_query, explicit_filters_json, normalized_filters_json, query_fingerprint,"
                + " normalization_version, execution_type, replay_of_feedback_id, status, degraded_reasons_json,"
                + " applied_excludes_json, search_config_json, config_version, parse_source, applied_rules_json,"
                + " filtered_json, created_at, updated_at) VALUES ("
                + id + ", " + ownerId + ", '" + queryText + "', '" + queryText + "',"
                + " '{\"broadcast_date\": {\"from\": \"2026-09-01\", \"to\": \"2026-09-15\"}}'::jsonb,"
                + " '{}'::jsonb, 'fp-" + id + "', 'v1', '" + executionType + "', "
                + (replayOfFeedbackId == null ? "NULL" : replayOfFeedbackId) + ", '" + status + "',"
                + ("degraded".equals(status) ? " '[\"dense_unavailable\"]'::jsonb," : " '[]'::jsonb,")
                + " '[]'::jsonb, '{}'::jsonb, 'cfg-v1', 'resolver', '[]'::jsonb, "
                + (filteredJson == null ? "NULL" : "'" + filteredJson + "'::jsonb")
                + ", '" + createdAt + "'::timestamptz, now())");
    }

    void result(long id, long executionId, long sceneId, int rank, String explainJson) {
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (" + id + ", " + executionId + ", " + sceneId + ", " + rank
                + ", '" + explainJson + "'::jsonb)");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
