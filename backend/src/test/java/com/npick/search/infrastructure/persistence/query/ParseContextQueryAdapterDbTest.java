package com.npick.search.infrastructure.persistence.query;

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

import com.npick.search.application.port.ParseContext;
import com.npick.search.application.port.ParseContextPort;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 대상. 후보 생성 전제 조회 seam(-81) 통합 테스트.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ParseContextQueryAdapter.class)
class ParseContextQueryAdapterDbTest {

    @Autowired
    private ParseContextPort port;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("신고의 상태·처리결과·담당자와 원 검색의 resolver 출력을 함께 읽는다")
    void readsStatusResolutionReviewerAndResolverOutput() {
        seed();

        ParseContext context = port.find(9901L).orElseThrow();

        assertThat(context.status()).isEqualTo("REVIEWING");
        assertThat(context.resolution()).isEqualTo("patch_parse");
        assertThat(context.reviewedById()).isEqualTo(9002L);
        assertThat(context.resolverOutputJson()).contains("query-resolver/v2");
    }

    @Test
    @Transactional
    @DisplayName("없는 신고면 비어 있다")
    void emptyWhenFeedbackMissing() {
        assertThat(port.find(-1L)).isEmpty();
    }

    private void seed() {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-9001', 'hash', '편집기자', 'editor', now(), now())");
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9002, 'reviewer-9002', 'hash', '검수자', 'reviewer', now(), now())");
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
                + " config_version, resolver_output_json, created_at, updated_at) VALUES (9701, 9001, 'q', 'q',"
                + " '{}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1', 'original', 'succeeded', '[]'::jsonb, '[]'::jsonb,"
                + " '{}'::jsonb, 'cfg-v1', '{\"schema_version\":\"query-resolver/v2\",\"intent\":\"scene_search\"}'::jsonb,"
                + " now(), now())");
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{\"score\":1}'::jsonb)");
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, created_at, review_started_at, updated_at) VALUES (9901, 9801, 9001,"
                + " 'REVIEWING', 9002, 'patch_parse', now(), now(), now())");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
