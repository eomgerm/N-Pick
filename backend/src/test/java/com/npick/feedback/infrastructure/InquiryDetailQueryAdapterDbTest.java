package com.npick.feedback.infrastructure;

import java.util.Optional;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.npick.feedback.application.query.InquiryDetail;
import com.npick.feedback.infrastructure.persistence.query.InquiryDetailQueryAdapter;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 컨테이너 대상 통합 테스트. NPICK_FEEDBACK_DB_TEST_URL 이 없으면 스킵된다.
@DataJpaTest(
        properties = {
            "spring.autoconfigure.exclude=",
            "spring.flyway.enabled=true",
            "spring.flyway.schemas=npick",
            "spring.flyway.default-schema=npick",
            "spring.flyway.create-schemas=true",
            "spring.jpa.properties.hibernate.default_schema=npick"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InquiryDetailQueryAdapter.class)
@EnabledIfEnvironmentVariable(named = "NPICK_FEEDBACK_DB_TEST_URL", matches = ".+")
class InquiryDetailQueryAdapterDbTest {

    @Autowired
    private InquiryDetailQueryAdapter adapter;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("NPICK_FEEDBACK_DB_TEST_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("NPICK_FEEDBACK_DB_TEST_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("NPICK_FEEDBACK_DB_TEST_PASSWORD"));
        // 테스트 DB 계정(npick_test)은 "$user" 스키마 관례를 안 따르므로 세션 search_path를 직접 건다.
        // 어댑터의 네이티브 SQL이 스키마 미한정 테이블명을 쓰기 때문에 필요하다.
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO npick, public");
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
        assertThat(detail.execution().queryText()).isNotBlank();
        assertThat(detail.sceneId()).isEqualTo(9301L);
        assertThat(detail.resultRank()).isEqualTo(1);
        assertThat(detail.execution().explicitFiltersJson()).containsIgnoringWhitespaces("2026-10-29");
        assertThat(detail.history().reviewerName()).isEqualTo("검수자9002");
        assertThat(detail.history().reviewerLoginId()).isEqualTo("reviewer-test-9002");
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
                INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source, registered_by_id, created_at, updated_at)
                VALUES (9101, 'broadcast', 'clips/9101/original', repeat('a', 64), 'none', 9001, now(), now())
                """);
        exec("""
                INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status, stage_states_json, created_at, updated_at)
                VALUES (9201, 9101, 1, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now())
                """);
        exec("""
                INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type, created_at, updated_at)
                VALUES (9301, 9101, 9201, 0, 1000, 'b_roll', now(), now())
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
                INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)
                VALUES (9501, 9101, 9301, 9401, now())
                """);
        exec("""
                INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)
                VALUES (9502, 9101, NULL, 9402, now())
                """);
        exec("""
                INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence, verification_status, created_at)
                VALUES (9601, 9501, 'ocr', 0.9, 'verified', now())
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
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
