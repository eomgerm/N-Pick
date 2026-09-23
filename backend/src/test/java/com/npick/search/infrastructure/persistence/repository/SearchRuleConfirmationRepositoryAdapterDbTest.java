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

import com.npick.search.domain.repository.SearchRuleConfirmationRepository;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 대상. 해석 교정 규칙 확정(-84) 통합 테스트.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SearchRuleConfirmationRepositoryAdapter.class)
class SearchRuleConfirmationRepositoryAdapterDbTest {

    private static final long FEEDBACK = 9901L;
    private static final long CANDIDATE_RULE = 6602L;
    private static final long REPLACED_RULE = 6601L;

    @Autowired
    private SearchRuleConfirmationRepository repository;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("신고 범위의 후보 규칙을 active=true 로 활성화한다")
    void activatesCandidate() {
        seed();

        int activated = repository.activate(FEEDBACK, CANDIDATE_RULE);

        assertThat(activated).isEqualTo(1);
        assertThat(activeFlag(CANDIDATE_RULE)).isEqualTo(true);
    }

    @Test
    @Transactional
    @DisplayName("교체 대상 규칙을 active=false 로 비활성화한다")
    void deactivatesReplaced() {
        seed();

        int deactivated = repository.deactivate(REPLACED_RULE);

        assertThat(deactivated).isEqualTo(1);
        assertThat(activeFlag(REPLACED_RULE)).isEqualTo(false);
    }

    @Test
    @Transactional
    @DisplayName("이미 그 상태인 규칙은 다시 바꾸지 않는다 (멱등)")
    void isIdempotent() {
        seed();
        repository.activate(FEEDBACK, CANDIDATE_RULE);
        repository.deactivate(REPLACED_RULE);

        assertThat(repository.activate(FEEDBACK, CANDIDATE_RULE)).isEqualTo(0);
        assertThat(repository.deactivate(REPLACED_RULE)).isEqualTo(0);
    }

    @Test
    @Transactional
    @DisplayName("다른 신고의 후보는 활성화하지 않는다")
    void doesNotActivateOtherFeedbackCandidate() {
        seed();

        int activated = repository.activate(8888L, CANDIDATE_RULE);

        assertThat(activated).isEqualTo(0);
        assertThat(activeFlag(CANDIDATE_RULE)).isEqualTo(false);
    }

    @Test
    @Transactional
    @DisplayName("discardPending 은 이 신고의 비활성 후보를 지우고 이미 활성인 규칙은 남긴다 (no_action 종료, S15P21A501-281)")
    void discardsOnlyPendingCandidatesOfThisFeedback() {
        seed();

        int discarded = repository.discardPending(FEEDBACK);

        assertThat(discarded).isEqualTo(1);
        assertThat(existsRule(CANDIDATE_RULE)).isFalse();
        assertThat(existsRule(REPLACED_RULE)).isTrue(); // active=true 라 지워지지 않는다
    }

    @Test
    @Transactional
    @DisplayName("대기 후보가 없는 신고는 discardPending 이 0행으로 조용히 통과한다")
    void discardPendingIsNoopWhenNothingPending() {
        assertThat(repository.discardPending(8888L)).isEqualTo(0);
    }

    private boolean existsRule(long ruleId) {
        return !em.createNativeQuery("SELECT 1 FROM search_rule WHERE search_rule_id = :id")
                .setParameter("id", ruleId)
                .getResultList()
                .isEmpty();
    }

    private Boolean activeFlag(long ruleId) {
        return (Boolean) em.createNativeQuery("SELECT active FROM search_rule WHERE search_rule_id = :id")
                .setParameter("id", ruleId)
                .getSingleResult();
    }

    private void seed() {
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
        // 교체 대상: 이미 활성인 기존 patch_parse 규칙
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at, condition_json, patch_json) VALUES (" + REPLACED_RULE
                + ", 'fp-r1', 'q', '{}'::jsonb, 'v1', 'patch_parse', NULL, 9901, true, now(), now(),"
                + " '{}'::jsonb, '{}'::jsonb)");
        // 후보: 비활성 patch_parse 규칙, 교체 대상을 가리킴
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at, condition_json, patch_json, replaces_rule_id, request_key)"
                + " VALUES (" + CANDIDATE_RULE + ", 'fp-r2', 'q', '{}'::jsonb, 'v1', 'patch_parse', NULL, 9901,"
                + " false, now(), now(), '{}'::jsonb, '{}'::jsonb, " + REPLACED_RULE + ", 'req-1')");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
