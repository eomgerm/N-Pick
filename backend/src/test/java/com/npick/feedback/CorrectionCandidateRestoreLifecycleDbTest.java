package com.npick.feedback;

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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 컨트롤러→feedback 오케스트레이터→tag·search 조회 UseCase→실DB 전 구간. 대기 교정 후보 복원 조회(-317).
@SpringBootTest
@AutoConfigureMockMvc
class CorrectionCandidateRestoreLifecycleDbTest {

    private static final AuthenticatedMember REVIEWER = new AuthenticatedMember(9002L, "reviewer01", "h", "REVIEWER");

    private static final String REPLACE_BODY = """
            {"operations":[
              {"action":"REJECT","scope":"SCENE","tagType":"location","matchValue":"서울","displayName":"서울"},
              {"action":"APPROVE","scope":"CLIP","tagType":"location","matchValue":"제주도","displayName":"제주도"}]}
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
    @DisplayName("이 신고의 대기 태그 근거·해석 후보·장면 제외 후보만 문자열 id 로 돌려준다 — 확정분·활성 규칙·다른 신고 후보는 빠진다")
    void returnsOnlyThisFeedbacksPendingCandidates() throws Exception {
        seed();
        mockMvc.perform(post("/api/v1/review/inquiries/9901/tag-correction-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REPLACE_BODY))
                .andExpect(status().isCreated());
        // 이미 확정된 검수자 근거(다른 태그)는 복원 대상이 아니다.
        jdbc.execute("INSERT INTO tag (tag_id, tag_type, match_value, name) VALUES (9409, 'location', '부산', '부산')");
        jdbc.execute("INSERT INTO tagging (tagging_id, clip_id, scene_id, tag_id, created_at)"
                + " VALUES (9509, 9101, 9301, 9409, now())");
        jdbc.execute("INSERT INTO tag_evidence (evidence_id, tagging_id, source, source_feedback_id, confidence,"
                + " verification_status, confirmed, created_at)"
                + " VALUES (9609, 9509, 'reviewer_feedback', 9901, NULL, 'verified', true, now())");
        // 대기 patch_parse(교체 대상 있음), 대기 exclude_scene, 이미 켜진 규칙(교체 대상), 다른 신고의 대기 후보.
        insertRule(6601, "patch_parse", null, 9901, true, null);
        insertRule(6602, "patch_parse", null, 9901, false, 6601L);
        insertRule(6603, "exclude_scene", 9301L, 9901, false, null);
        insertRule(6604, "exclude_scene", 9301L, 9902, false, null);

        mockMvc.perform(get("/api/v1/review/inquiries/9901/correction-candidates")
                        .with(user(REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags", hasSize(2)))
                .andExpect(jsonPath("$.data.tags[0].action").value("REJECT"))
                .andExpect(jsonPath("$.data.tags[0].scope").value("SCENE"))
                .andExpect(jsonPath("$.data.tags[0].tagType").value("location"))
                .andExpect(jsonPath("$.data.tags[0].matchValue").value("서울"))
                .andExpect(jsonPath("$.data.tags[0].displayName").value("서울"))
                .andExpect(jsonPath("$.data.tags[0].evidenceId").isString())
                .andExpect(jsonPath("$.data.tags[0].taggingId").isString())
                .andExpect(jsonPath("$.data.tags[1].action").value("APPROVE"))
                .andExpect(jsonPath("$.data.tags[1].scope").value("CLIP"))
                .andExpect(jsonPath("$.data.tags[1].matchValue").value("제주도"))
                .andExpect(jsonPath("$.data.parsePatches", hasSize(1)))
                .andExpect(jsonPath("$.data.parsePatches[0].searchRuleId").value("6602"))
                .andExpect(jsonPath("$.data.parsePatches[0].replacesRuleId").value("6601"))
                .andExpect(jsonPath("$.data.parsePatches[0].condition.version").value("parse-rule/v1"))
                .andExpect(jsonPath("$.data.parsePatches[0].patch.ops").isArray())
                .andExpect(jsonPath("$.data.sceneExcludes", hasSize(1)))
                .andExpect(jsonPath("$.data.sceneExcludes[0].searchRuleId").value("6603"))
                .andExpect(jsonPath("$.data.sceneExcludes[0].targetSceneId").value("9301"));

        // 대기 후보만 있는 태깅은 상세의 현재 태그에 섞이지 않는다 — 복원은 위 조회로만 한다.
        mockMvc.perform(get("/api/v1/review/inquiries/9901").with(user(REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence", hasSize(1)))
                .andExpect(jsonPath("$.data.evidence[0].matchValue").value("부산"));
    }

    @Test
    @Transactional
    @DisplayName("교체 대상이 없는 해석 후보는 replacesRuleId 가 null 이고, 후보가 없으면 빈 목록이다")
    void emptyListsAndNullReplaces() throws Exception {
        seed();
        insertRule(6602, "patch_parse", null, 9901, false, null);

        mockMvc.perform(get("/api/v1/review/inquiries/9901/correction-candidates")
                        .with(user(REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags", hasSize(0)))
                .andExpect(jsonPath("$.data.sceneExcludes", hasSize(0)))
                .andExpect(jsonPath("$.data.parsePatches[0].replacesRuleId").value(nullValue()));
    }

    @Test
    @Transactional
    @DisplayName("담당 검수자가 아니면 403 FEEDBACK_403_002, 없는 신고는 404 FEEDBACK_404_002, 편집기자는 보안 계층이 403")
    void guards() throws Exception {
        seed();

        mockMvc.perform(get("/api/v1/review/inquiries/9901/correction-candidates")
                        .with(user(new AuthenticatedMember(9003L, "reviewer02", "h", "REVIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FEEDBACK_403_002"));
        mockMvc.perform(get("/api/v1/review/inquiries/1/correction-candidates").with(user(REVIEWER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEEDBACK_404_002"));
        mockMvc.perform(get("/api/v1/review/inquiries/9901/correction-candidates")
                        .with(user(new AuthenticatedMember(9001L, "editor01", "h", "EDITOR"))))
                .andExpect(status().isForbidden());
    }

    private void insertRule(
            long ruleId, String action, Long targetSceneId, long feedbackId, boolean active, Long replaces) {
        boolean patch = "patch_parse".equals(action);
        jdbc.update(
                "INSERT INTO search_rule (search_rule_id, query_fingerprint, normalized_query,"
                        + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                        + " active, created_at, updated_at, condition_json, patch_json, replaces_rule_id, request_key)"
                        + " VALUES (?, 'fp-9701', 'q', '{}'::jsonb, 'v1', ?, ?, ?, ?, now(), now(),"
                        + " CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)",
                ruleId,
                action,
                targetSceneId,
                feedbackId,
                active,
                patch ? "{\"version\":\"parse-rule/v1\",\"all\":[]}" : null,
                patch ? "{\"version\":\"parse-rule/v1\",\"ops\":[]}" : null,
                replaces,
                "key-" + ruleId);
    }

    private void seed() {
        jdbc.execute("INSERT INTO member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9002, 'reviewer01', 'h', '검수자', 'reviewer', now(), now())");
        jdbc.execute("INSERT INTO member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9003, 'reviewer02', 'h', '검수자2', 'reviewer', now(), now())");
        jdbc.execute("INSERT INTO clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at) VALUES (9101, 'broadcast', 'clips/9101/o',"
                + " repeat('a', 64), 'none', 9002, now(), now())");
        jdbc.execute("INSERT INTO pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at) VALUES (9201, 9101, 3, 'v1', 'succeeded',"
                + " '{}'::jsonb, now(), now())");
        jdbc.execute("INSERT INTO scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (9301, 9101, 9201, 42000, 49000, 'b_roll', now(), now())");
        for (int i = 1; i <= 2; i++) {
            jdbc.execute("INSERT INTO search_execution (search_execution_id, searched_by_id, query_text,"
                    + " normalized_query, explicit_filters_json, normalized_filters_json, query_fingerprint,"
                    + " normalization_version, execution_type, status, degraded_reasons_json, applied_excludes_json,"
                    + " search_config_json, config_version, created_at, updated_at) VALUES (970" + i + ", 9002, 'q',"
                    + " 'q', '{}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1', 'original', 'succeeded', '[]'::jsonb,"
                    + " '[]'::jsonb, '{}'::jsonb, 'cfg-v1', now(), now())");
            jdbc.execute("INSERT INTO search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                    + " explain_json) VALUES (980" + i + ", 970" + i + ", 9301, 1, '{\"score\":1}'::jsonb)");
            jdbc.execute("INSERT INTO feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                    + " resolution, created_at, review_started_at, updated_at) VALUES (990" + i + ", 980" + i
                    + ", 9002, 'REVIEWING', 9002, 'correction', now(), now(), now())");
        }
    }
}
