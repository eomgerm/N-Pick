package com.npick.feedback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.security.AuthenticatedMember;
import com.npick.support.NpickPostgres;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 컨트롤러→서비스→조회→실DB 전 구간. 「내 문의 기록」 상세(S15P21A501-185).
@SpringBootTest
@AutoConfigureMockMvc
class MyInquiryDetailHttpDbTest {

    private static final AuthenticatedMember OWNER = new AuthenticatedMember(9001L, "editor01", "h", "EDITOR");

    // 검색은 9001 이 했는데 9003 이 그 검색 결과에 자기 명의로 문의를 만든 계정.
    private static final AuthenticatedMember CROSS_SEARCHER = new AuthenticatedMember(9003L, "editor03", "h", "EDITOR");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("본인 소유 CLOSED/no_action 문의 상세를 전체 필드로 반환한다")
    void returnsFullDetailForOwner() throws Exception {
        seed();

        mockMvc.perform(get("/api/v1/inquiries/9902").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.data.feedback_id").value("9902"))
                .andExpect(jsonPath("$.data.search_execution_id").value("9701"))
                .andExpect(jsonPath("$.data.search_result_id").value("9802"))
                .andExpect(jsonPath("$.data.query_text").value("테스트 질의"))
                .andExpect(jsonPath("$.data.comment").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.resolution").value("no_action"))
                .andExpect(jsonPath("$.data.resolution_note").value("사유 없음으로 처리"))
                .andExpect(jsonPath("$.data.review_started_at").exists())
                .andExpect(jsonPath("$.data.closed_at").exists())
                .andExpect(jsonPath("$.data.scene.scene_id").value("9302"))
                .andExpect(jsonPath("$.data.scene.clip_id").value("9101"))
                .andExpect(jsonPath("$.data.scene.start_time_ms").value(49000))
                .andExpect(jsonPath("$.data.scene.end_time_ms").value(55000))
                .andExpect(jsonPath("$.data.explicit_filters").isEmpty())
                .andExpect(jsonPath("$.data.snapshot_status").value("unavailable"))
                .andExpect(jsonPath("$.data.result_snapshot").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @Transactional
    @DisplayName("본인 소유 OPEN 문의는 처리 사유·시각이 null 로 명시된다")
    void openInquiryHasNullResolutionFields() throws Exception {
        seed();

        mockMvc.perform(get("/api/v1/inquiries/9901").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.resolution").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.resolution_note").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.review_started_at").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.closed_at").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @Transactional
    @DisplayName("타인 소유 문의는 404 다")
    void returns404ForOtherOwner() throws Exception {
        seed();

        mockMvc.perform(get("/api/v1/inquiries/9903").with(user(OWNER))).andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    @DisplayName("작성자여도 원 검색자가 타인이면 404 — 남의 검색어·필터 노출 차단")
    void returns404WhenOriginalSearcherIsAnother() throws Exception {
        seed();

        // 9903 은 9003 이 만든 문의지만, 참조하는 검색(9701)은 9001 이 실행했다.
        // 작성자 조건만 보면 통과하나, 응답에 9001 의 query_text·explicit_filters 가 실려 노출된다.
        mockMvc.perform(get("/api/v1/inquiries/9903").with(user(CROSS_SEARCHER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    @DisplayName("존재하지 않는 문의도 타인 소유와 동일하게 404 다")
    void returns404ForNonexistent() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries/88888").with(user(OWNER))).andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    @DisplayName("경로 ID 가 음수면 400 으로 거부한다")
    void rejectsInvalidPathId() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries/-1").with(user(OWNER))).andExpect(status().isBadRequest());
    }

    private void seed() {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor01', 'hash', '편집기자9001', 'editor', now(), now())");
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9003, 'editor03', 'hash', '편집기자9003', 'editor', now(), now())");
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at)"
                + " VALUES (9101, 'broadcast', 'clips/9101/o', repeat('a', 64), 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at)"
                + " VALUES (9201, 9101, 3, 'v1', 'succeeded', '{}'::jsonb, now(), now())");
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
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, comment, status,"
                + " created_at, updated_at) VALUES (9901, 9801, 9001, '이상한 결과 같아요', 'OPEN', now(), now())");
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, resolution_note, created_at, review_started_at, closed_at, updated_at)"
                + " VALUES (9902, 9802, 9001, 'CLOSED', 9003, 'no_action', '사유 없음으로 처리', now(), now(), now(), now())");
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, comment, status,"
                + " created_at, updated_at) VALUES (9903, 9803, 9003, '타인 문의', 'OPEN', now(), now())");
    }

    private void exec(String sql) {
        jdbc.execute(sql);
    }
}
