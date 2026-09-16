package com.npick.feedback.infrastructure.persistence.query;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.npick.feedback.application.port.ExcludeTargetValidityPort;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL 대상. 장면 제외 확정의 대상 장면 유효성 재확인(-85, F-14) 통합 테스트.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ExcludeTargetValidityQueryAdapter.class)
class ExcludeTargetValidityQueryAdapterDbTest {

    private static final long RULE = 6601L;

    @Autowired
    private ExcludeTargetValidityPort port;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("대상 장면이 현재 제공 중인 처리에 속하면 유효하다")
    void activeSceneIsValid() {
        seedActiveExcludeRule();

        assertThat(port.targetSceneActive(RULE)).isTrue();
    }

    @Test
    @Transactional
    @DisplayName("재처리로 새 처리가 서고 대상 장면이 옛 처리 소속이면 사라진 것으로 본다")
    void sceneFromSupersededRunIsGone() {
        seedActiveExcludeRule();
        // 재추출: 새 처리(9202)가 활성으로 승격. 제외 대상 장면(9301)은 옛 처리(9201) 소속이라 이제 검색 대상 아님.
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at) VALUES (9202, 9101, 4, 'v1', 'succeeded',"
                + " '{}'::jsonb, now(), now())");
        exec("UPDATE npick.clip SET active_pipeline_run_id = 9202 WHERE clip_id = 9101");

        assertThat(port.targetSceneActive(RULE)).isFalse();
    }

    @Test
    @Transactional
    @DisplayName("논리 삭제된 클립의 장면은 유효하지 않다")
    void deletedClipSceneIsInvalid() {
        seedActiveExcludeRule();
        exec("UPDATE npick.clip SET deleted_at = now() WHERE clip_id = 9101");

        assertThat(port.targetSceneActive(RULE)).isFalse();
    }

    @Test
    @Transactional
    @DisplayName("없는 규칙은 유효하지 않다")
    void unknownRuleIsInvalid() {
        seedActiveExcludeRule();

        assertThat(port.targetSceneActive(9999L)).isFalse();
    }

    private void seedActiveExcludeRule() {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-9001', 'hash', '편집기자', 'editor', now(), now())");
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at) VALUES (9101, 'broadcast', 'clips/9101/o',"
                + " repeat('a', 64), 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at) VALUES (9201, 9101, 3, 'v1', 'succeeded',"
                + " '{}'::jsonb, now(), now())");
        exec("UPDATE npick.clip SET active_pipeline_run_id = 9201 WHERE clip_id = 9101");
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (9301, 9101, 9201, 42000, 49000, 'b_roll', now(), now())");
        exec("INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text, normalized_query,"
                + " explicit_filters_json, normalized_filters_json, query_fingerprint, normalization_version,"
                + " execution_type, status, degraded_reasons_json, applied_excludes_json, search_config_json,"
                + " config_version, created_at, updated_at) VALUES (9701, 9001, 'q', 'q', '{}'::jsonb, '{}'::jsonb,"
                + " 'fp-9701', 'v1', 'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1',"
                + " now(), now())");
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{\"score\":1}'::jsonb)");
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, created_at, review_started_at, updated_at) VALUES (9901, 9801, 9001,"
                + " 'REVIEWING', 9001, 'exclude_scene', now(), now(), now())");
        // 제외 후보 규칙 — 대상 장면 9301
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at) VALUES (" + RULE + ", 'fp-r1', 'q', '{}'::jsonb, 'v1',"
                + " 'exclude_scene', 9301, 9901, false, now(), now())");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
