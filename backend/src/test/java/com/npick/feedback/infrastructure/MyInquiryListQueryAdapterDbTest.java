package com.npick.feedback.infrastructure;

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

import com.npick.feedback.application.query.MyInquiryListItem;
import com.npick.feedback.infrastructure.persistence.query.MyInquiryListQueryAdapter;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 통합 테스트. 「내 문의 기록」 목록(S15P21A501-185) — 소유자 격리·정렬·페이지·필드 매핑.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MyInquiryListQueryAdapter.class)
class MyInquiryListQueryAdapterDbTest {

    @Autowired
    private MyInquiryListQueryAdapter adapter;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("본인 소유 문의만 접수 최신순(동시각은 id DESC)으로 반환하고 필드를 채운다")
    void findsOwnOnlyOrderedWithFields() {
        seed();

        List<MyInquiryListItem> mine = adapter.findByOwner(9001, 0, 20);

        // 타인(9003) 문의 9903 은 섞이지 않는다. 동일 시각이라 feedback_id DESC 로 9902 가 먼저.
        assertThat(mine).extracting(MyInquiryListItem::feedbackId).containsExactly(9902L, 9901L);

        MyInquiryListItem open = mine.get(1);
        assertThat(open.feedbackId()).isEqualTo(9901L);
        assertThat(open.searchExecutionId()).isEqualTo(9701L);
        assertThat(open.searchResultId()).isEqualTo(9801L);
        assertThat(open.queryText()).isEqualTo("테스트 질의");
        assertThat(open.comment()).isEqualTo("이상한 결과 같아요");
        assertThat(open.status()).isEqualTo("OPEN");
        assertThat(open.resolution()).isNull();
        assertThat(open.createdAt()).isNotNull();
        assertThat(open.updatedAt()).isNotNull();
        assertThat(open.scene().sceneId()).isEqualTo(9301L);
        assertThat(open.scene().clipId()).isEqualTo(9101L);
        assertThat(open.scene().startTimeMs()).isEqualTo(42000L);
        assertThat(open.scene().endTimeMs()).isEqualTo(49000L);

        // comment 없는(REVIEWING) 문의는 comment=null, resolution 채워짐.
        MyInquiryListItem reviewing = mine.get(0);
        assertThat(reviewing.feedbackId()).isEqualTo(9902L);
        assertThat(reviewing.comment()).isNull();
        assertThat(reviewing.resolution()).isEqualTo("no_action");
    }

    @Test
    @Transactional
    @DisplayName("소유자별 전체 건수를 세고, 페이지 크기로 자른다")
    void countsAndPaginatesByOwner() {
        seed();

        assertThat(adapter.countByOwner(9001)).isEqualTo(2);
        assertThat(adapter.countByOwner(9003)).isEqualTo(1);
        assertThat(adapter.countByOwner(9999)).isZero();

        assertThat(adapter.findByOwner(9001, 0, 1)).hasSize(1);
        assertThat(adapter.findByOwner(9001, 1, 1)).hasSize(1);
        assertThat(adapter.findByOwner(9001, 2, 1)).isEmpty();
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
        // 9001 소유 2건
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, comment, status,"
                + " created_at, updated_at)"
                + " VALUES (9901, 9801, 9001, '이상한 결과 같아요', 'OPEN', now(), now())");
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, created_at, review_started_at, updated_at)"
                + " VALUES (9902, 9802, 9001, 'REVIEWING', 9003, 'no_action', now(), now(), now())");
        // 9003 소유 1건 — 격리 확인용
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, comment, status,"
                + " created_at, updated_at)"
                + " VALUES (9903, 9803, 9003, '타인 문의', 'OPEN', now(), now())");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
