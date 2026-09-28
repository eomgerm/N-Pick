package com.npick.feedback.infrastructure;

import java.util.Optional;
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

import com.npick.feedback.application.query.InquiryDetail;
import com.npick.feedback.application.query.SceneEvidence;
import com.npick.feedback.infrastructure.persistence.query.InquiryDetailQueryAdapter;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 대상 통합 테스트. Testcontainers 가 컨테이너를 띄운다(NpickPostgres).
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InquiryDetailQueryAdapter.class)
class InquiryDetailQueryAdapterDbTest {

    @Autowired
    private InquiryDetailQueryAdapter adapter;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("신고 상세 조회는 클립 상속 태깅 근거·당시 explain_json·종료 사유를 함께 반환한다")
    void findsInquiryDetailWithClipInheritedEvidence() {
        seed();

        Optional<InquiryDetail> found = adapter.findById(9901L);

        assertThat(found).isPresent();
        InquiryDetail detail = found.get();
        assertThat(detail.resultExplainJson()).containsIgnoringWhitespaces("\"score\":1");
        assertThat(detail.resolutionNote()).isEqualTo("조치 불필요");
        assertThat(detail.evidence()).anyMatch(e -> "CLIP".equals(e.scope())).anyMatch(e -> "SCENE".equals(e.scope()));
        assertThat(detail.evidence()).allMatch(e -> "keyword".equals(e.tagType()));
        assertThat(detail.evidence())
                .extracting(e -> e.matchValue())
                .containsExactly("scene-tag-9401", "rejected-tag-9403", "clip-tag-9402");
        assertThat(detail.execution().queryText()).isNotBlank();
        assertThat(detail.sceneId()).isEqualTo(9301L);
        assertThat(detail.scene().clipId()).isEqualTo(9101L);
        assertThat(detail.scene().clipTitle()).isEqualTo("뉴스9 교통 상황");
        assertThat(detail.scene().startTimeMs()).isEqualTo(42_000L);
        assertThat(detail.scene().endTimeMs()).isEqualTo(49_000L);
        assertThat(detail.scene().pipelineRunId()).isEqualTo(9201L);
        assertThat(detail.scene().processingNo()).isEqualTo(3);
        assertThat(detail.resultRank()).isEqualTo(1);
        assertThat(detail.execution().explicitFiltersJson()).containsIgnoringWhitespaces("2026-10-29");
        assertThat(detail.history().reviewerName()).isEqualTo("검수자9002");
        assertThat(detail.history().reviewerLoginId()).isEqualTo("reviewer-test-9002");
    }

    @Test
    @Transactional
    @DisplayName("한 태그에 확정 근거가 여러 건이어도 태그당 한 번만 반환하고, 출처는 모으고 검증 상태는 verified 우선이다 (S15P21A501-235)")
    void groupsEvidenceByTaggingWithoutDuplicates() {
        seed(); // tagging 9501 에 확정 근거 2건(9601 ocr/verified, 9603 vlm/unverified)

        InquiryDetail detail = adapter.findById(9901L).orElseThrow();

        // 태그당 한 줄 — 같은 taggingId 가 여러 번 나오지 않는다
        assertThat(detail.evidence()).extracting(SceneEvidence::taggingId).doesNotHaveDuplicates();

        SceneEvidence sceneTag = detail.evidence().stream()
                .filter(e -> e.taggingId() == 9501L)
                .findFirst()
                .orElseThrow();
        assertThat(sceneTag.sources()).containsExactlyInAnyOrder("ocr", "vlm");
        assertThat(sceneTag.verifiedState()).isEqualTo("verified"); // 하나라도 verified 면 verified
    }

    @Test
    @Transactional
    @DisplayName("확정 근거가 검수자 반려 판단뿐인 태그는 NULL 로 뭉개지 않고 'rejected' 를 반환한다 (S15P21A501-235)")
    void keepsReviewerRejectedVerificationStatus() {
        seed(); // tagging 9503 에 확정 근거가 reviewer_feedback/rejected 하나뿐

        InquiryDetail detail = adapter.findById(9901L).orElseThrow();

        SceneEvidence rejectedTag = detail.evidence().stream()
                .filter(e -> e.taggingId() == 9503L)
                .findFirst()
                .orElseThrow();
        assertThat(rejectedTag.verifiedState()).isEqualTo("rejected"); // verified/unverified 아니어도 NULL 로 뭉개지 않는다
        assertThat(rejectedTag.sources()).containsExactly("reviewer_feedback");
    }

    @Test
    @Transactional
    @DisplayName("확정 근거 없이 대기 중인 검수자 후보만 있거나 근거가 아예 없는 tagging 은 현재 태그로 내지 않는다 (S15P21A501-317)")
    void excludesTaggingsWithoutConfirmedEvidence() {
        seed();
        exec("""
                INSERT INTO npick.tag (tag_id, tag_type, match_value, name)
                VALUES (9404, 'keyword', 'pending-tag-9404', '대기태그9404')
                """);
        exec("""
                INSERT INTO npick.tag (tag_id, tag_type, match_value, name)
                VALUES (9405, 'keyword', 'orphan-tag-9405', '고아태그9405')
                """);
        exec("""
                INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)
                VALUES (9504, 9101, 9301, 9404, now())
                """);
        // 후보 취소(-309)로 근거가 지워지고 tagging 만 남은 경우.
        exec("""
                INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)
                VALUES (9505, 9101, NULL, 9405, now())
                """);
        exec("""
                INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, source_feedback_id, confidence,
                    verification_status, confirmed, created_at)
                VALUES (9605, 9504, 'reviewer_feedback', 9901, NULL, 'verified', false, now())
                """);
        // 확정 근거가 있는 tagging 에 붙은 대기 후보는 출처·상태에 섞이지 않는다.
        exec("""
                INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, source_feedback_id, confidence,
                    verification_status, confirmed, created_at)
                VALUES (9606, 9502, 'reviewer_feedback', 9901, NULL, 'rejected', false, now())
                """);

        InquiryDetail detail = adapter.findById(9901L).orElseThrow();

        assertThat(detail.evidence()).extracting(SceneEvidence::taggingId).containsExactly(9501L, 9503L, 9502L);
        SceneEvidence clipTag = detail.evidence().stream()
                .filter(e -> e.taggingId() == 9502L)
                .findFirst()
                .orElseThrow();
        assertThat(clipTag.sources()).containsExactly("user_input");
        assertThat(clipTag.verifiedState()).isEqualTo("unverified");
    }

    private void seed() {
        exec("""
                INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)
                VALUES (9001, 'editor-test-9001', 'hash', '편집기자9001', 'editor', now(), now())
                """);
        exec("""
                INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)
                VALUES (9002, 'reviewer-test-9002', 'hash', '검수자9002', 'reviewer', now(), now())
                """);
        exec("""
                INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, title, transcript_source, registered_by_id, created_at, updated_at)
                VALUES (9101, 'broadcast', 'clips/9101/original', repeat('a', 64), '뉴스9 교통 상황', 'none', 9001, now(), now())
                """);
        exec("""
                INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status, stage_states_json, created_at, updated_at)
                VALUES (9201, 9101, 3, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now())
                """);
        exec("""
                INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type, created_at, updated_at)
                VALUES (9301, 9101, 9201, 42000, 49000, 'b_roll', now(), now())
                """);
        exec("""
                INSERT INTO npick.tag (tag_id, tag_type, match_value, name)
                VALUES (9401, 'keyword', 'scene-tag-9401', '장면태그9401')
                """);
        exec("""
                INSERT INTO npick.tag (tag_id, tag_type, match_value, name)
                VALUES (9402, 'keyword', 'clip-tag-9402', '클립태그9402')
                """);
        exec("""
                INSERT INTO npick.tag (tag_id, tag_type, match_value, name)
                VALUES (9403, 'keyword', 'rejected-tag-9403', '반려태그9403')
                """);
        exec("""
                INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)
                VALUES (9501, 9101, 9301, 9401, now())
                """);
        exec("""
                INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)
                VALUES (9502, 9101, NULL, 9402, now())
                """);
        // 확정 근거가 검수자 반려 판단 하나뿐인 tagging(F-10 장면별 예외). 이 태그의 검증 상태는 'rejected' 로 나가야 한다(S15P21A501-235).
        exec("""
                INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)
                VALUES (9503, 9101, 9301, 9403, now())
                """);
        exec("""
                INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence, verification_status, created_at)
                VALUES (9601, 9501, 'ocr', 0.9, 'verified', now())
                """);
        // 같은 태그(9501)에 확정 근거가 하나 더 — 여러 키프레임에서 잡히는 경우. 1:N 조인이 tagging 을 곱하던 원인(S15P21A501-235).
        exec("""
                INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence, verification_status, created_at)
                VALUES (9603, 9501, 'vlm', NULL, 'unverified', now())
                """);
        exec("""
                INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence, verification_status, created_at)
                VALUES (9602, 9502, 'user_input', NULL, 'unverified', now())
                """);
        exec("""
                INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text, normalized_query,
                    explicit_filters_json, normalized_filters_json, query_fingerprint, normalization_version,
                    execution_type, status, degraded_reasons_json, applied_excludes_json, search_config_json,
                    config_version, created_at, updated_at)
                VALUES (9701, 9001, '테스트 질의', '테스트 질의', '{"date":"2026-10-29"}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1',
                    'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1', now(), now())
                """);
        exec("""
                INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank, explain_json)
                VALUES (9801, 9701, 9301, 1, '{"score":1}'::jsonb)
                """);
        exec("""
                INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,
                    resolution, resolution_note, created_at, review_started_at, updated_at)
                VALUES (9901, 9801, 9001, 'REVIEWING', 9002, 'no_action', '조치 불필요', now(), now(), now())
                """);
        // feedback 뒤에 넣는다 — source_feedback_id 가 feedback(9901) 을 참조한다.
        // reviewer_feedback 반려 근거: source_feedback_id 필수, confidence NULL(ck_evidence_review_shape).
        exec("""
                INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, source_feedback_id, confidence, verification_status, created_at)
                VALUES (9604, 9503, 'reviewer_feedback', 9901, NULL, 'rejected', now())
                """);
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
