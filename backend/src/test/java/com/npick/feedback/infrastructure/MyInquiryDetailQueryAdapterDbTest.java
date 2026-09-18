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

import com.npick.feedback.application.query.MyInquiryDetail;
import com.npick.feedback.infrastructure.persistence.query.MyInquiryDetailQueryAdapter;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 통합 테스트. 「내 문의 기록」 상세(S15P21A501-185) — 소유자 조건·필드 매핑.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MyInquiryDetailQueryAdapter.class)
class MyInquiryDetailQueryAdapterDbTest {

    @Autowired
    private MyInquiryDetailQueryAdapter adapter;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("본인 소유 CLOSED/no_action 문의는 처리 사유·종료 시각까지 채워 반환한다")
    void findsOwnFullFieldsForClosedNoAction() {
        seed();

        Optional<MyInquiryDetail> found = adapter.findByOwner(9902, 9001);

        assertThat(found).isPresent();
        MyInquiryDetail detail = found.get();
        assertThat(detail.feedbackId()).isEqualTo(9902L);
        assertThat(detail.searchExecutionId()).isEqualTo(9701L);
        assertThat(detail.searchResultId()).isEqualTo(9802L);
        assertThat(detail.queryText()).isEqualTo("테스트 질의");
        assertThat(detail.comment()).isNull();
        assertThat(detail.status()).isEqualTo("CLOSED");
        assertThat(detail.resolution()).isEqualTo("no_action");
        assertThat(detail.resolutionNote()).isEqualTo("사유 없음으로 처리");
        assertThat(detail.createdAt()).isNotNull();
        assertThat(detail.updatedAt()).isNotNull();
        assertThat(detail.reviewStartedAt()).isNotNull();
        assertThat(detail.closedAt()).isNotNull();
        assertThat(detail.explicitFiltersJson()).isEqualTo("{}");
        assertThat(detail.scene().sceneId()).isEqualTo(9302L);
        assertThat(detail.scene().clipId()).isEqualTo(9101L);
        assertThat(detail.scene().startTimeMs()).isEqualTo(49000L);
        assertThat(detail.scene().endTimeMs()).isEqualTo(55000L);
    }

    @Test
    @Transactional
    @DisplayName("본인 소유 OPEN 문의는 처리 사유·시각이 전부 null 이다")
    void findsOwnOpenWithNullResolutionFields() {
        seed();

        Optional<MyInquiryDetail> found = adapter.findByOwner(9901, 9001);

        assertThat(found).isPresent();
        MyInquiryDetail detail = found.get();
        assertThat(detail.status()).isEqualTo("OPEN");
        assertThat(detail.resolution()).isNull();
        assertThat(detail.resolutionNote()).isNull();
        assertThat(detail.reviewStartedAt()).isNull();
        assertThat(detail.closedAt()).isNull();
    }

    @Test
    @Transactional
    @DisplayName("본인 소유 문의는 당시 순위와 explain_json 원문을 함께 반환한다")
    void returnsRecordedSnapshotFields() {
        seed();

        MyInquiryDetail detail = adapter.findByOwner(9902, 9001).orElseThrow();

        assertThat(detail.resultRank()).isEqualTo(2);
        assertThat(detail.resultExplainJson()).contains("\"score\"");
    }

    @Test
    @Transactional
    @DisplayName("타인 소유 문의는 존재해도 빈 값을 반환한다")
    void returnsEmptyWhenNotOwner() {
        seed();

        assertThat(adapter.findByOwner(9903, 9001)).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("존재하지 않는 문의도 타인 소유와 동일하게 빈 값을 반환한다")
    void returnsEmptyWhenNotFound() {
        seed();

        assertThat(adapter.findByOwner(88888, 9001)).isEmpty();
    }

    private void seed() {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-9001', 'hash', '편집기자9001', 'editor', now(), now())");
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9003, 'editor-9003', 'hash', '편집기자9003', 'editor', now(), now())");
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at)"
                + " VALUES (9101, 'broadcast', 'clips/9101/original', repeat('a', 64), 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at)"
                + " VALUES (9201, 9101, 3, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now())");
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (9301, 9101, 9201, 42000, 49000, 'b_roll', now(), now())");
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (9302, 9101, 9201, 49000, 55000, 'b_roll', now(), now())");
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (9303, 9101, 9201, 55000, 60000, 'b_roll', now(), now())");
        exec("INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text, normalized_query,"
                + " explicit_filters_json, normalized_filters_json, query_fingerprint, normalization_version,"
                + " execution_type, status, degraded_reasons_json, applied_excludes_json, search_config_json,"
                + " config_version, created_at, updated_at)"
                + " VALUES (9701, 9001, '테스트 질의', '테스트 질의', '{}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1',"
                + " 'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1', now(), now())");
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{\"score\":1}'::jsonb)");
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9802, 9701, 9302, 2, '{\"score\":2}'::jsonb)");
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9803, 9701, 9303, 3, '{\"score\":3}'::jsonb)");
        // 9001 소유: OPEN(9901), CLOSED/no_action(9902)
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, comment, status,"
                + " created_at, updated_at)"
                + " VALUES (9901, 9801, 9001, '이상한 결과 같아요', 'OPEN', now(), now())");
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, resolution_note, created_at, review_started_at, closed_at, updated_at)"
                + " VALUES (9902, 9802, 9001, 'CLOSED', 9003, 'no_action', '사유 없음으로 처리', now(), now(), now(), now())");
        // 9003 소유 — 격리 확인용
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, comment, status,"
                + " created_at, updated_at)"
                + " VALUES (9903, 9803, 9003, '타인 문의', 'OPEN', now(), now())");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
