package com.npick.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.security.AuthenticatedMember;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 컨트롤러→서비스→실DB 전 구간. 규칙 사용 중단(-86) end-to-end.
@SpringBootTest
@AutoConfigureMockMvc
class SearchRuleDeactivationLifecycleDbTest {

    private static final AuthenticatedMember REVIEWER =
            new AuthenticatedMember(9002L, "reviewer01", "h", "REVIEWER");
    private static final AuthenticatedMember EDITOR =
            new AuthenticatedMember(9001L, "editor01", "h", "EDITOR");

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
    @DisplayName("검수자가 활성 규칙을 중단하면 200 이고 active 가 꺼진다")
    void deactivates() throws Exception {
        seedActiveRule();

        patchActive(REVIEWER, "{\"active\":false,\"reason\":\"장소로만 해석돼 사건 검색을 놓침\"}")
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT active FROM npick.search_rule WHERE search_rule_id = 6601",
                        Boolean.class))
                .isFalse();
    }

    @Test
    @Transactional
    @DisplayName("검증 없이 재활성화(active=true)는 409 로 거부하고 상태를 바꾸지 않는다")
    void rejectsReactivation() throws Exception {
        seedActiveRule();
        jdbc.update("UPDATE npick.search_rule SET active = false WHERE search_rule_id = 6601");

        patchActive(REVIEWER, "{\"active\":true,\"reason\":\"다시 켜고 싶다\"}")
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT active FROM npick.search_rule WHERE search_rule_id = 6601",
                        Boolean.class))
                .isFalse();
    }

    @Test
    @Transactional
    @DisplayName("사유가 없으면 400 으로 막는다")
    void reasonRequired() throws Exception {
        seedActiveRule();

        patchActive(REVIEWER, "{\"active\":false}").andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    @DisplayName("편집기자는 규칙을 중단할 수 없다")
    void editorForbidden() throws Exception {
        seedActiveRule();

        patchActive(EDITOR, "{\"active\":false,\"reason\":\"x\"}").andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions patchActive(AuthenticatedMember member, String body)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/review/search-rules/6601")
                .with(user(member))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void seedActiveRule() {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor01', 'hash', '편집기자', 'editor', now(), now())");
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9002, 'reviewer01', 'h', '검수자', 'reviewer', now(), now())");
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
                + " 'REVIEWING', 9002, 'patch_parse', now(), now(), now())");
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at, condition_json, patch_json) VALUES (6601, 'fp-r1', 'q',"
                + " '{}'::jsonb, 'v1', 'patch_parse', NULL, 9901, true, now(), now(), '{}'::jsonb, '{}'::jsonb)");
    }

    private void exec(String sql) {
        jdbc.execute(sql);
    }
}
