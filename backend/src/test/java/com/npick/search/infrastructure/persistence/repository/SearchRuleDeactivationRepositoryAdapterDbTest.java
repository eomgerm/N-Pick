package com.npick.search.infrastructure.persistence.repository;

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

import com.npick.search.domain.repository.SearchRuleDeactivationRepository;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 대상. 규칙 사용 중단(-86) 통합 테스트.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SearchRuleDeactivationRepositoryAdapter.class)
class SearchRuleDeactivationRepositoryAdapterDbTest {

    private static final long RULE = 6601L;

    @Autowired
    private SearchRuleDeactivationRepository repository;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("활성 규칙을 끄고, 끈 뒤에는 활성 규칙 집합에서 빠진다")
    void deactivatesActiveRule() {
        seedActiveRule();

        int off = repository.deactivate(RULE);

        assertThat(off).isEqualTo(1);
        assertThat(activeFlag(RULE)).isFalse();
        Number activeCount = (Number)
                em.createNativeQuery("SELECT count(*) FROM search_rule WHERE search_rule_id = :id AND active = true")
                        .setParameter("id", RULE)
                        .getSingleResult();
        assertThat(activeCount.intValue()).isEqualTo(0);
    }

    @Test
    @Transactional
    @DisplayName("이미 꺼진 규칙은 다시 끄지 않는다 (멱등·기대 상태 비교)")
    void isIdempotent() {
        seedActiveRule();
        repository.deactivate(RULE);

        assertThat(repository.deactivate(RULE)).isEqualTo(0);
        assertThat(activeFlag(RULE)).isFalse();
    }

    @Test
    @Transactional
    @DisplayName("규칙 존재 여부를 구분한다")
    void exists() {
        seedActiveRule();

        assertThat(repository.exists(RULE)).isTrue();
        assertThat(repository.exists(9999L)).isFalse();
    }

    private Boolean activeFlag(long ruleId) {
        return (Boolean) em.createNativeQuery("SELECT active FROM search_rule WHERE search_rule_id = :id")
                .setParameter("id", ruleId)
                .getSingleResult();
    }

    private void seedActiveRule() {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-9001', 'hash', '편집기자', 'editor', now(), now())");
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at) VALUES (9101, 'broadcast', 'clips/9101/o',"
                + " repeat('a', 64), 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at) VALUES (9201, 9101, 3, 'v1', 'succeeded',"
                + " '{}'::jsonb, now(), now())");
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
                + " 'REVIEWING', 9001, 'patch_parse', now(), now(), now())");
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at, condition_json, patch_json) VALUES (" + RULE + ", 'fp-r1', 'q',"
                + " '{}'::jsonb, 'v1', 'patch_parse', NULL, 9901, true, now(), now(), '{}'::jsonb, '{}'::jsonb)");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
