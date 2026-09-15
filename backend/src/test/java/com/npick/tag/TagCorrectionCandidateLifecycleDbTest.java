package com.npick.tag;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 컨트롤러→서비스→어댑터→실DB 전 구간. 태그 교정 후보(교체=반려+추가) 생성 end-to-end (-160).
@SpringBootTest
@AutoConfigureMockMvc
class TagCorrectionCandidateLifecycleDbTest {

    private static final String REPLACE_BODY = """
            {"operations":[
              {"action":"REJECT","scope":"SCENE","tagType":"location","matchValue":"서울","displayName":"서울"},
              {"action":"APPROVE","scope":"SCENE","tagType":"location","matchValue":"제주도","displayName":"제주도"}]}
            """;

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
    @DisplayName("교체 후보가 confirmed=false reviewer_feedback 근거 둘(반려·승인)로 저장된다")
    void createsUnconfirmedReplaceJudgments() throws Exception {
        seed();

        mockMvc.perform(post("/api/v1/review/inquiries/9901/tag-correction-candidate")
                        .with(user(new AuthenticatedMember(9002L, "reviewer01", "h", "REVIEWER")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REPLACE_BODY))
                .andExpect(status().isCreated());

        Integer unconfirmed = jdbc.queryForObject(
                "SELECT count(*) FROM tag_evidence WHERE source_feedback_id = 9901 AND confirmed = false"
                        + " AND source = 'reviewer_feedback'",
                Integer.class);
        assertThat(unconfirmed).isEqualTo(2);

        Integer rejected = jdbc.queryForObject(
                "SELECT count(*) FROM tag_evidence te JOIN tagging tg ON tg.tagging_id = te.tagging_id"
                        + " JOIN tag t ON t.tag_id = tg.tag_id"
                        + " WHERE te.source_feedback_id = 9901 AND te.verification_status = 'rejected'"
                        + " AND t.match_value = '서울' AND tg.scene_id = 9301",
                Integer.class);
        assertThat(rejected).isEqualTo(1);

        Integer verified = jdbc.queryForObject(
                "SELECT count(*) FROM tag_evidence te JOIN tagging tg ON tg.tagging_id = te.tagging_id"
                        + " JOIN tag t ON t.tag_id = tg.tag_id"
                        + " WHERE te.source_feedback_id = 9901 AND te.verification_status = 'verified'"
                        + " AND t.match_value = '제주도' AND tg.scene_id = 9301",
                Integer.class);
        assertThat(verified).isEqualTo(1);
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
                + " config_version, created_at, updated_at) VALUES (9701, 9002, 'q', 'q', '{}'::jsonb, '{}'::jsonb,"
                + " 'fp-9701', 'v1', 'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1',"
                + " now(), now())");
        jdbc.execute("INSERT INTO search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{\"score\":1}'::jsonb)");
        jdbc.execute("INSERT INTO feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, created_at, review_started_at, updated_at) VALUES (9901, 9801, 9002,"
                + " 'REVIEWING', 9002, 'tag_correction', now(), now(), now())");
    }
}
