package com.npick.search;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 컨트롤러→서비스→어댑터→실DB 전 구간. patch_parse 후보만 취소하고 같은 신고의 exclude_scene 후보는 남긴다 (-309).
@SpringBootTest
@AutoConfigureMockMvc
class DiscardParsePatchCandidateLifecycleDbTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    private final AuthenticatedMember reviewer = new AuthenticatedMember(9002L, "reviewer01", "h", "REVIEWER");

    @Test
    @Transactional
    @DisplayName("patch_parse 대기 후보만 지우고 같은 신고의 exclude_scene 후보는 남긴다")
    void discardsOnlyPatchParseAndKeepsExcludeScene() throws Exception {
        seed();
        // 같은 신고에 patch_parse 대기 후보와 exclude_scene 대기 후보를 나란히 둔다.
        insertPatchParseRule(8801L, 9901L, false);
        jdbc.execute("INSERT INTO search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at) VALUES (8802, 'fp-9701', 'q', '{}'::jsonb, 'v1',"
                + " 'exclude_scene', 9301, 9901, false, now(), now())");

        mockMvc.perform(delete("/api/v1/review/inquiries/9901/parse-patch-candidate")
                        .with(user(reviewer))
                        .with(csrf()))
                .andExpect(status().isOk());

        Integer patchParse = jdbc.queryForObject(
                "SELECT count(*) FROM search_rule WHERE source_feedback_id = 9901 AND action = 'patch_parse'",
                Integer.class);
        assertThat(patchParse).isZero();

        Integer excludeScene = jdbc.queryForObject(
                "SELECT count(*) FROM search_rule WHERE source_feedback_id = 9901 AND action = 'exclude_scene'",
                Integer.class);
        assertThat(excludeScene).isEqualTo(1);
    }

    private void insertPatchParseRule(long ruleId, long feedbackId, boolean active) {
        jdbc.execute("INSERT INTO search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, source_feedback_id, active,"
                + " created_at, updated_at, condition_json, patch_json) VALUES (" + ruleId + ", '', '',"
                + " '{}'::jsonb, '', 'patch_parse', " + feedbackId + ", " + active + ", now(), now(),"
                + " '{}'::jsonb, '{}'::jsonb)");
    }

    private void seed() {
        jdbc.execute("INSERT INTO member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9002, 'reviewer01', 'h', '검수자', 'reviewer', now(), now())");
        jdbc.execute("INSERT INTO clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at) VALUES (9101, 'broadcast', 'clips/9101/o',"
                + " repeat('a', 64), 'none', 9002, now(), now())");
        jdbc.execute("INSERT INTO pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at) VALUES (9201, 9101, 3, 'v1', 'succeeded',"
                + " '{}'::jsonb, now(), now())");
        jdbc.execute("INSERT INTO scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (9301, 9101, 9201, 42000, 49000, 'b_roll', now(), now())");
        jdbc.execute("INSERT INTO search_execution (search_execution_id, searched_by_id, query_text, normalized_query,"
                + " explicit_filters_json, normalized_filters_json, query_fingerprint, normalization_version,"
                + " execution_type, status, degraded_reasons_json, applied_excludes_json, search_config_json,"
                + " config_version, created_at, updated_at) VALUES (9701, 9002, 'q', 'q',"
                + " '{}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1', 'original', 'succeeded', '[]'::jsonb, '[]'::jsonb,"
                + " '{}'::jsonb, 'cfg-v1', now(), now())");
        jdbc.execute("INSERT INTO search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{\"score\":1}'::jsonb)");
        jdbc.execute("INSERT INTO feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, created_at, review_started_at, updated_at) VALUES (9901, 9801, 9002,"
                + " 'REVIEWING', 9002, 'patch_parse', now(), now(), now())");
    }
}
