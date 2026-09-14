package com.npick.search.infrastructure.persistence.repository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.npick.search.domain.model.ParseRuleCandidate;
import com.npick.search.domain.repository.ParseRuleCandidateRepository;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실 PostgreSQL(paradedb) 대상. patch_parse 후보 쓰기 어댑터(-81) 통합 테스트.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ParseRuleCandidateRepositoryAdapter.class)
class ParseRuleCandidateRepositoryAdapterDbTest {

    private static final String CONDITION =
            "{\"syntax_version\":\"parse-rule/v1\",\"resolution_schema_version\":\"query-resolver/v2\","
                    + "\"all\":[{\"axis\":\"locations\",\"op\":\"has_value\",\"value\":\"○○공장\"}]}";
    private static final String PATCH = "{\"syntax_version\":\"parse-rule/v1\",\"operations\":"
            + "[{\"op\":\"remove_item\",\"axis\":\"locations\",\"type\":\"location\",\"value\":\"○○공장\"}]}";

    @Autowired
    private ParseRuleCandidateRepository repository;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("후보를 active=false patch_parse 행으로 저장하고 신고·요청키로 다시 찾는다")
    void savesInactiveCandidateAndFindsById() {
        seedFeedback();

        long id = repository.save(new ParseRuleCandidate(9901L, "rk-1", CONDITION, PATCH, null));

        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT action, active, source_feedback_id, request_key, CAST(condition_json AS text) "
                                + "FROM search_rule WHERE search_rule_id = :id")
                .setParameter("id", id)
                .getSingleResult();
        assertThat(row[0]).isEqualTo("patch_parse");
        assertThat(row[1]).isEqualTo(false);
        assertThat(((Number) row[2]).longValue()).isEqualTo(9901L);
        assertThat(row[3]).isEqualTo("rk-1");
        assertThat((String) row[4]).contains("parse-rule/v1");
        assertThat(repository.findId(9901L, "rk-1")).contains(id);
    }

    @Test
    @Transactional
    @DisplayName("같은 신고·요청키로 두 번 저장하면 유니크 제약이 막는다")
    void rejectsDuplicateRequestKey() {
        seedFeedback();
        repository.save(new ParseRuleCandidate(9901L, "rk-1", CONDITION, PATCH, null));

        assertThatThrownBy(() -> repository.save(new ParseRuleCandidate(9901L, "rk-1", CONDITION, PATCH, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void seedFeedback() {
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
                + " config_version, created_at, updated_at) VALUES (9701, 9001, 'q', 'q', '{}'::jsonb,"
                + " '{}'::jsonb, 'fp-9701', 'v1', 'original', 'succeeded', '[]'::jsonb, '[]'::jsonb,"
                + " '{}'::jsonb, 'cfg-v1', now(), now())");
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{\"score\":1}'::jsonb)");
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, created_at, review_started_at, updated_at) VALUES (9901, 9801, 9001,"
                + " 'REVIEWING', 9001, 'patch_parse', now(), now(), now())");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
