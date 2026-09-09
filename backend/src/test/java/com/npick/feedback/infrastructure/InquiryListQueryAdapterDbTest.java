package com.npick.feedback.infrastructure;

import java.util.List;
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

import com.npick.feedback.application.query.InquiryListItem;
import com.npick.feedback.infrastructure.persistence.query.InquiryListQueryAdapter;

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
@Import(InquiryListQueryAdapter.class)
@EnabledIfEnvironmentVariable(named = "NPICK_FEEDBACK_DB_TEST_URL", matches = ".+")
class InquiryListQueryAdapterDbTest {

    @Autowired
    private InquiryListQueryAdapter adapter;

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
    @DisplayName("상태로 필터링하면 해당 상태의 신고만 필드가 채워져 반환된다")
    void findsByStatusFiltersToMatchingRowsOnly() {
        seed();

        List<InquiryListItem> open = adapter.findByStatus("OPEN", 0, 20);

        assertThat(open).hasSize(1);
        InquiryListItem item = open.get(0);
        assertThat(item.feedbackId()).isEqualTo(9901L);
        assertThat(item.status()).isEqualTo("OPEN");
        assertThat(item.resolution()).isNull();
        assertThat(item.queryText()).isNotBlank();
        assertThat(item.sceneId()).isEqualTo(9301L);
        assertThat(item.hasComment()).isTrue();
    }

    @Test
    @Transactional
    @DisplayName("상태를 넘기지 않으면(null) 모든 상태의 신고를 반환한다 - null-status CAST 경로 실증")
    void findsAllStatusesWhenStatusIsNull() {
        seed();

        List<InquiryListItem> all = adapter.findByStatus(null, 0, 20);

        assertThat(all).extracting(InquiryListItem::feedbackId).containsExactlyInAnyOrder(9901L, 9902L);
    }

    @Test
    @Transactional
    @DisplayName("countByStatus는 현재 필터 기준, countGroupedByStatus는 상태별 전체 집계를 반환한다")
    void countsReflectFilterAndGrouping() {
        seed();

        assertThat(adapter.countByStatus("OPEN")).isEqualTo(1);
        assertThat(adapter.countByStatus(null)).isEqualTo(2);

        com.npick.feedback.application.query.StatusCounts counts = adapter.countGroupedByStatus();
        assertThat(counts.open()).isEqualTo(1);
        assertThat(counts.reviewing()).isEqualTo(1);
        assertThat(counts.closed()).isZero();
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
                INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type, created_at, updated_at)
                VALUES (9302, 9101, 9201, 1000, 2000, 'b_roll', now(), now())
                """);
        exec("""
                INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text, normalized_query,
                    explicit_filters_json, normalized_filters_json, query_fingerprint, normalization_version,
                    execution_type, status, degraded_reasons_json, applied_excludes_json, search_config_json,
                    config_version, created_at, updated_at)
                VALUES (9701, 9001, '테스트 질의', '테스트 질의', '{}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1',
                    'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1', now(), now())
                """);
        exec("""
                INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank, explain_json)
                VALUES (9801, 9701, 9301, 1, '{"score":1}'::jsonb)
                """);
        exec("""
                INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank, explain_json)
                VALUES (9802, 9701, 9302, 2, '{"score":2}'::jsonb)
                """);
        exec("""
                INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, comment, status, created_at, updated_at)
                VALUES (9901, 9801, 9001, '이상한 결과 같아요', 'OPEN', now(), now())
                """);
        exec("""
                INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,
                    resolution, created_at, review_started_at, updated_at)
                VALUES (9902, 9802, 9001, 'REVIEWING', 9002, 'no_action', now(), now(), now())
                """);
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
