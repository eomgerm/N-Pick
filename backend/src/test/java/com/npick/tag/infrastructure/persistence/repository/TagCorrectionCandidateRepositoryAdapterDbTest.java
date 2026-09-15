package com.npick.tag.infrastructure.persistence.repository;

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
import com.npick.tag.domain.model.ReviewerTagJudgment;
import com.npick.tag.domain.repository.TagCorrectionCandidateRepository;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 대상. 검수자 태그 교정 후보 쓰기(-160) 통합 테스트.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TagCorrectionCandidateRepositoryAdapter.class)
class TagCorrectionCandidateRepositoryAdapterDbTest {

    @Autowired
    private TagCorrectionCandidateRepository repository;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    private ReviewerTagJudgment sceneJudgment(String status) {
        return new ReviewerTagJudgment(9901L, 9101L, 9301L, "location", "제주도", "제주도", status);
    }

    @Test
    @Transactional
    @DisplayName("검수자 판단을 confirmed=false reviewer_feedback 근거로 저장하고 태그·장면태깅을 잇는다")
    void savesUnconfirmedReviewerEvidence() {
        seed();

        long evidenceId = repository.addJudgment(sceneJudgment("verified"));

        Object[] row = (Object[])
                em.createNativeQuery("SELECT te.source, te.verification_status, te.confirmed, te.source_feedback_id, "
                                + "tg.scene_id, t.tag_type, t.match_value "
                                + "FROM tag_evidence te JOIN tagging tg ON tg.tagging_id = te.tagging_id "
                                + "JOIN tag t ON t.tag_id = tg.tag_id WHERE te.evidence_id = :id")
                        .setParameter("id", evidenceId)
                        .getSingleResult();
        assertThat(row[0]).isEqualTo("reviewer_feedback");
        assertThat(row[1]).isEqualTo("verified");
        assertThat(row[2]).isEqualTo(false);
        assertThat(((Number) row[3]).longValue()).isEqualTo(9901L);
        assertThat(((Number) row[4]).longValue()).isEqualTo(9301L);
        assertThat(row[5]).isEqualTo("location");
        assertThat(row[6]).isEqualTo("제주도");
    }

    @Test
    @Transactional
    @DisplayName("같은 태그·범위에 판단을 두 번 하면 태그·태깅은 재사용하고 근거만 두 개 쌓인다")
    void reusesTagAndTaggingAcrossJudgments() {
        seed();

        repository.addJudgment(sceneJudgment("verified"));
        repository.addJudgment(sceneJudgment("rejected"));

        Number tags = (Number)
                em.createNativeQuery("SELECT count(*) FROM tag WHERE tag_type = 'location' AND match_value = '제주도'")
                        .getSingleResult();
        Number taggings =
                (Number) em.createNativeQuery("SELECT count(*) FROM tagging WHERE clip_id = 9101 AND scene_id = 9301")
                        .getSingleResult();
        Number evidences =
                (Number) em.createNativeQuery("SELECT count(*) FROM tag_evidence WHERE source_feedback_id = 9901")
                        .getSingleResult();
        assertThat(tags.intValue()).isEqualTo(1);
        assertThat(taggings.intValue()).isEqualTo(1);
        assertThat(evidences.intValue()).isEqualTo(2);
    }

    @Test
    @Transactional
    @DisplayName("클립 범위 판단은 tagging.scene_id 가 NULL 이다")
    void clipScopeHasNullScene() {
        seed();

        long evidenceId = repository.addJudgment(
                new ReviewerTagJudgment(9901L, 9101L, null, "location", "제주도", "제주도", "verified"));

        Object sceneId = em.createNativeQuery(
                        "SELECT tg.scene_id FROM tag_evidence te JOIN tagging tg ON tg.tagging_id = te.tagging_id "
                                + "WHERE te.evidence_id = :id")
                .setParameter("id", evidenceId)
                .getSingleResult();
        assertThat(sceneId).isNull();
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
                + " 'REVIEWING', 9001, 'tag_correction', now(), now(), now())");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
