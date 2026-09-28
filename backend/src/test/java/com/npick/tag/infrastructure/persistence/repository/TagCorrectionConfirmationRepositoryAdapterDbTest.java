package com.npick.tag.infrastructure.persistence.repository;

import java.util.List;
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

import com.npick.support.NpickPostgres;
import com.npick.tag.domain.repository.TagCorrectionConfirmationRepository;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 대상. 검수자 태그 교정 후보 확정(-84) 통합 테스트.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TagCorrectionConfirmationRepositoryAdapter.class)
class TagCorrectionConfirmationRepositoryAdapterDbTest {

    @Autowired
    private TagCorrectionConfirmationRepository repository;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("신고 범위의 미확정 근거를 confirmed=true 로 올린다")
    void confirmsPendingEvidence() {
        seed(9901L, 7901L, false);

        int confirmed = repository.confirm(9901L, List.of(7901L));

        assertThat(confirmed).isEqualTo(1);
        assertThat(confirmedFlag(7901L)).isEqualTo(true);
    }

    @Test
    @Transactional
    @DisplayName("이미 확정된 근거는 다시 확정하지 않는다 (멱등)")
    void isIdempotent() {
        seed(9901L, 7901L, false);
        repository.confirm(9901L, List.of(7901L));

        int again = repository.confirm(9901L, List.of(7901L));

        assertThat(again).isEqualTo(0);
        assertThat(confirmedFlag(7901L)).isEqualTo(true);
    }

    @Test
    @Transactional
    @DisplayName("다른 신고의 근거는 id 가 넘어와도 확정하지 않는다")
    void doesNotConfirmOtherFeedbackEvidence() {
        seed(9901L, 7901L, false);

        int confirmed = repository.confirm(8888L, List.of(7901L));

        assertThat(confirmed).isEqualTo(0);
        assertThat(confirmedFlag(7901L)).isEqualTo(false);
    }

    @Test
    @Transactional
    @DisplayName("discardPending 은 이 신고의 미확정 근거를 지우고 확정된 근거는 남긴다 (no_action 종료, S15P21A501-281)")
    void discardsOnlyPendingEvidenceOfThisFeedback() {
        seed(9901L, 7901L, false);
        // 같은 신고·같은 태깅에 이미 확정된 근거가 하나 더 있는 경우(혼재) — discardPending 이 confirmed=true 는 건드리지 않는지 본다.
        exec("INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence, verification_status,"
                + " source_feedback_id, confirmed, created_at) VALUES (7902, 7801, 'reviewer_feedback',"
                + " NULL, 'verified', 9901, true, now())");

        int discarded = repository.discardPending(9901L);

        assertThat(discarded).isEqualTo(1);
        assertThat(existsEvidence(7901L)).isFalse();
        assertThat(existsEvidence(7902L)).isTrue();
    }

    @Test
    @Transactional
    @DisplayName("대기 근거가 없는 신고는 discardPending 이 0행으로 조용히 통과한다")
    void discardPendingIsNoopWhenNothingPending() {
        assertThat(repository.discardPending(9902L)).isEqualTo(0);
    }

    @Test
    @Transactional
    @DisplayName("discardPendingOne 은 지정한 대기 근거 하나만 지우고 같은 신고의 다른 대기·확정 근거와 다른 신고의 근거는 남긴다 (S15P21A501-309)")
    void discardPendingOneDeletesOnlyTargetEvidence() {
        seed(9901L, 7901L, false);
        // 같은 신고의 다른 대기 근거, 같은 신고의 확정 근거
        insertEvidence(7902L, 9901L, false);
        insertEvidence(7903L, 9901L, true);
        // 다른 신고(같은 결과 행, 다른 신고자)의 대기 근거
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9002, 'editor-9002', 'hash', '편집기자2', 'editor', now(), now())");
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, created_at, review_started_at, updated_at) VALUES (9902, 9801, 9002,"
                + " 'REVIEWING', 9001, 'tag_correction', now(), now(), now())");
        insertEvidence(7904L, 9902L, false);

        int discarded = repository.discardPendingOne(9901L, 7901L);

        assertThat(discarded).isEqualTo(1);
        assertThat(existsEvidence(7901L)).isFalse();
        assertThat(existsEvidence(7902L)).isTrue();
        assertThat(existsEvidence(7903L)).isTrue();
        assertThat(existsEvidence(7904L)).isTrue();
    }

    @Test
    @Transactional
    @DisplayName("discardPendingOne 은 확정 근거·다른 신고의 근거·없는 근거면 0행으로 조용히 통과한다")
    void discardPendingOneIsNoopForConfirmedOrForeignEvidence() {
        seed(9901L, 7901L, true);

        assertThat(repository.discardPendingOne(9901L, 7901L)).isEqualTo(0);
        assertThat(repository.discardPendingOne(8888L, 7901L)).isEqualTo(0);
        assertThat(repository.discardPendingOne(9901L, 1L)).isEqualTo(0);
        assertThat(existsEvidence(7901L)).isTrue();
    }

    private void insertEvidence(long evidenceId, long feedbackId, boolean confirmed) {
        exec("INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence, verification_status,"
                + " source_feedback_id, confirmed, created_at) VALUES (" + evidenceId + ", 7801, 'reviewer_feedback',"
                + " NULL, 'verified', " + feedbackId + ", " + confirmed + ", now())");
    }

    private boolean existsEvidence(long evidenceId) {
        return !em.createNativeQuery("SELECT 1 FROM tag_evidence WHERE evidence_id = :id")
                .setParameter("id", evidenceId)
                .getResultList()
                .isEmpty();
    }

    private Boolean confirmedFlag(long evidenceId) {
        return (Boolean) em.createNativeQuery("SELECT confirmed FROM tag_evidence WHERE evidence_id = :id")
                .setParameter("id", evidenceId)
                .getSingleResult();
    }

    private void seed(long feedbackId, long evidenceId, boolean confirmed) {
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
                + " resolution, created_at, review_started_at, updated_at) VALUES (" + feedbackId + ", 9801, 9001,"
                + " 'REVIEWING', 9001, 'tag_correction', now(), now(), now())");
        exec("INSERT INTO npick.tag (tag_id, tag_type, match_value, name) VALUES (7701, 'location', '제주도', '제주도')");
        exec("INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)"
                + " VALUES (7801, 9101, 9301, 7701, now())");
        exec("INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence, verification_status,"
                + " source_feedback_id, confirmed, created_at) VALUES (" + evidenceId + ", 7801, 'reviewer_feedback',"
                + " NULL, 'verified', " + feedbackId + ", " + confirmed + ", now())");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
