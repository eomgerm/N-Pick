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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 컨트롤러→서비스→(tag·search 확정 UseCase)→실DB 전 구간. 교정 확정(-84) end-to-end.
@SpringBootTest
@AutoConfigureMockMvc
class CorrectionConfirmLifecycleDbTest {

    private static final AuthenticatedMember REVIEWER =
            new AuthenticatedMember(9002L, "reviewer01", "h", "REVIEWER");

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
    @DisplayName("태그 교정 확정: 근거가 confirmed=true 로 오르고 신고가 검증 실행에 연결되어 종료된다")
    void confirmsTagCorrection() throws Exception {
        seedCommon("tag_correction");
        seedTagCandidate();
        seedReplay("{\"resolution\":\"tag_correction\",\"approved_evidence_ids\":[7901],"
                + "\"approved_rule_id\":null,\"replaced_rule_id\":null,\"state_fingerprint\":\"rules=;tags=\"}");

        confirm(9702L).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = 7901",
                        Boolean.class))
                .isTrue();
        assertClosedAndLinked();
        // 태그만 교정하면 created_rule_id 는 NULL 이다.
        assertThat(jdbc.queryForObject("SELECT created_rule_id FROM npick.feedback WHERE feedback_id = 9901",
                        Long.class))
                .isNull();
    }

    @Test
    @Transactional
    @DisplayName("해석 교정 확정: 후보 규칙이 활성화되고 교체 대상이 비활성화되며 신고가 종료된다")
    void confirmsParseRule() throws Exception {
        seedCommon("patch_parse");
        seedRuleCandidate();
        seedReplay("{\"resolution\":\"patch_parse\",\"approved_evidence_ids\":[],"
                + "\"approved_rule_id\":6602,\"replaced_rule_id\":6601,\"state_fingerprint\":\"rules=6601;tags=\"}");

        confirm(9702L).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT active FROM npick.search_rule WHERE search_rule_id = 6602",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT active FROM npick.search_rule WHERE search_rule_id = 6601",
                        Boolean.class))
                .isFalse();
        assertClosedAndLinked();
        assertThat(jdbc.queryForObject("SELECT created_rule_id FROM npick.feedback WHERE feedback_id = 9901",
                        Long.class))
                .isEqualTo(6602L);
    }

    @Test
    @Transactional
    @DisplayName("patch_parse 확정: 규칙 활성화·교체와 태그 근거 확정을 한 트랜잭션으로 (F-09 조합)")
    void confirmsParseRuleWithTags() throws Exception {
        seedCommon("patch_parse");
        seedRuleCandidate();
        seedTagCandidate();
        seedReplay("{\"resolution\":\"patch_parse\",\"approved_evidence_ids\":[7901],"
                + "\"approved_rule_id\":6602,\"replaced_rule_id\":6601,\"state_fingerprint\":\"rules=6601;tags=\"}");

        confirm(9702L).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT active FROM npick.search_rule WHERE search_rule_id = 6602",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT active FROM npick.search_rule WHERE search_rule_id = 6601",
                        Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = 7901",
                        Boolean.class))
                .isTrue();
        assertClosedAndLinked();
        assertThat(jdbc.queryForObject("SELECT created_rule_id FROM npick.feedback WHERE feedback_id = 9901",
                        Long.class))
                .isEqualTo(6602L);
    }

    @Test
    @Transactional
    @DisplayName("장면 제외 확정: 대상 장면이 유효하면 제외 규칙 활성화·신고 종료·created_rule_id 기록")
    void confirmsExcludeScene() throws Exception {
        seedCommon("exclude_scene");
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id = 9201 WHERE clip_id = 9101");
        seedExcludeRule();
        seedReplay("{\"resolution\":\"exclude_scene\",\"approved_evidence_ids\":[],"
                + "\"approved_rule_id\":6601,\"replaced_rule_id\":null,\"state_fingerprint\":\"rules=;tags=\"}");

        confirm(9702L).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT active FROM npick.search_rule WHERE search_rule_id = 6601",
                        Boolean.class))
                .isTrue();
        assertClosedAndLinked();
        assertThat(jdbc.queryForObject("SELECT created_rule_id FROM npick.feedback WHERE feedback_id = 9901",
                        Long.class))
                .isEqualTo(6601L);
    }

    @Test
    @Transactional
    @DisplayName("장면 제외 확정: 대상 장면이 재처리로 사라졌으면 409 거부하고 신고는 reviewing 유지")
    void rejectsExcludeWhenSceneGone() throws Exception {
        seedCommon("exclude_scene");
        // 재처리: 새 처리(9202)가 활성으로 승격. 제외 대상 장면(9301)은 옛 처리(9201) 소속이라 이제 검색 대상 아님.
        jdbc.execute("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version,"
                + " status, stage_states_json, created_at, updated_at) VALUES (9202, 9101, 4, 'v1', 'succeeded',"
                + " '{}'::jsonb, now(), now())");
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id = 9202 WHERE clip_id = 9101");
        seedExcludeRule();
        seedReplay("{\"resolution\":\"exclude_scene\",\"approved_evidence_ids\":[],"
                + "\"approved_rule_id\":6601,\"replaced_rule_id\":null,\"state_fingerprint\":\"rules=;tags=\"}");

        confirm(9702L).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT active FROM npick.search_rule WHERE search_rule_id = 6601",
                        Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM npick.feedback WHERE feedback_id = 9901", String.class))
                .isEqualTo("REVIEWING");
    }

    @Test
    @Transactional
    @DisplayName("검증 이후 상태가 바뀌었으면 확정을 거부하고 아무것도 바꾸지 않는다")
    void rejectsWhenStateDrifted() throws Exception {
        seedCommon("tag_correction");
        seedTagCandidate();
        // 저장된 지문이 현재 지문(rules=;tags=)과 다르다 — 검증 이후 상태가 바뀐 상황
        seedReplay("{\"resolution\":\"tag_correction\",\"approved_evidence_ids\":[7901],"
                + "\"approved_rule_id\":null,\"replaced_rule_id\":null,\"state_fingerprint\":\"rules=stale;tags=\"}");

        confirm(9702L).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = 7901",
                        Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM npick.feedback WHERE feedback_id = 9901", String.class))
                .isEqualTo("REVIEWING");
    }

    @Test
    @Transactional
    @DisplayName("검증 이후 다른 종류(exclude_scene) 활성 규칙이 바뀌면 지문에 잡혀 재검증을 요구한다")
    void rejectsWhenExcludeRuleDrifted() throws Exception {
        seedCommon("tag_correction");
        seedTagCandidate();
        // 검증 스냅샷은 활성 규칙이 없던 상태(rules=;tags=). 그 사이 exclude_scene 규칙이 활성화됐다 —
        // 지문이 patch_parse 만 세면 이 변경을 놓쳐 stale 확정이 통과한다(P1). 전체 활성 규칙을 세므로 재검증을 요구해야 한다.
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at) VALUES (6605, 'fp-x9', 'q', '{}'::jsonb, 'v1', 'exclude_scene',"
                + " 9301, 9901, true, now(), now())");
        seedReplay("{\"resolution\":\"tag_correction\",\"approved_evidence_ids\":[7901],"
                + "\"approved_rule_id\":null,\"replaced_rule_id\":null,\"state_fingerprint\":\"rules=;tags=\"}");

        confirm(9702L).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = 7901",
                        Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM npick.feedback WHERE feedback_id = 9901", String.class))
                .isEqualTo("REVIEWING");
    }

    @Test
    @Transactional
    @DisplayName("스냅샷이 판정별 필수 필드를 갖추지 못하면(근거 0개 tag_correction) 확정 근거로 삼지 않고 404 거부한다")
    void rejectsIncompleteSnapshot() throws Exception {
        seedCommon("tag_correction");
        seedTagCandidate();
        // approved_evidence_ids 가 빈 tag_correction — 근거 0개로 CLOSED 되면 안 된다(F-12).
        seedReplay("{\"resolution\":\"tag_correction\",\"approved_evidence_ids\":[],"
                + "\"approved_rule_id\":null,\"replaced_rule_id\":null,\"state_fingerprint\":\"rules=;tags=\"}");

        confirm(9702L).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = 7901",
                        Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM npick.feedback WHERE feedback_id = 9901", String.class))
                .isEqualTo("REVIEWING");
    }

    @Test
    @Transactional
    @DisplayName("근거 id 가 정수·양수가 아니면(문자열·객체) 0 으로 통과시키지 않고 404 거부한다")
    void rejectsNonIntegerEvidenceId() throws Exception {
        seedCommon("tag_correction");
        seedTagCandidate();
        // approved_evidence_ids 에 문자열 — asLong() 이 0 으로 바꿔 통과하던 경로. 이제 타입 검증으로 거부한다.
        seedReplay("{\"resolution\":\"tag_correction\",\"approved_evidence_ids\":[\"abc\"],"
                + "\"approved_rule_id\":null,\"replaced_rule_id\":null,\"state_fingerprint\":\"rules=;tags=\"}");

        confirm(9702L).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = 7901",
                        Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM npick.feedback WHERE feedback_id = 9901", String.class))
                .isEqualTo("REVIEWING");
    }

    private org.springframework.test.web.servlet.ResultActions confirm(long executionId) throws Exception {
        return mockMvc.perform(post("/api/v1/review/inquiries/9901/confirm")
                .with(user(REVIEWER))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"executionId\":" + executionId + "}"));
    }

    private void assertClosedAndLinked() {
        assertThat(jdbc.queryForObject("SELECT status FROM npick.feedback WHERE feedback_id = 9901", String.class))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                        "SELECT verified_by_execution_id FROM npick.feedback WHERE feedback_id = 9901", Long.class))
                .isEqualTo(9702L);
    }

    private void seedCommon(String resolution) {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-9001', 'hash', '편집기자', 'editor', now(), now())");
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
                + " 'REVIEWING', 9002, '" + resolution + "', now(), now(), now())");
    }

    private void seedTagCandidate() {
        exec("INSERT INTO npick.tag (tag_id, tag_type, match_value, name) VALUES (7701, 'location', '제주도', '제주도')");
        exec("INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)"
                + " VALUES (7801, 9101, 9301, 7701, now())");
        exec("INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence, verification_status,"
                + " source_feedback_id, confirmed, created_at) VALUES (7901, 7801, 'reviewer_feedback', NULL,"
                + " 'verified', 9901, false, now())");
    }

    private void seedRuleCandidate() {
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at, condition_json, patch_json) VALUES (6601, 'fp-r1', 'q',"
                + " '{}'::jsonb, 'v1', 'patch_parse', NULL, 9901, true, now(), now(), '{}'::jsonb, '{}'::jsonb)");
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at, condition_json, patch_json, replaces_rule_id, request_key)"
                + " VALUES (6602, 'fp-r2', 'q', '{}'::jsonb, 'v1', 'patch_parse', NULL, 9901, false, now(), now(),"
                + " '{}'::jsonb, '{}'::jsonb, 6601, 'req-1')");
    }

    private void seedExcludeRule() {
        // 비활성 exclude_scene 후보 규칙, 대상 장면 9301
        exec("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at) VALUES (6601, 'fp-x1', 'q', '{}'::jsonb, 'v1', 'exclude_scene',"
                + " 9301, 9901, false, now(), now())");
    }

    private void seedReplay(String verificationContextJson) {
        exec("INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text, normalized_query,"
                + " explicit_filters_json, normalized_filters_json, query_fingerprint, normalization_version,"
                + " execution_type, replay_of_feedback_id, status, degraded_reasons_json, applied_excludes_json,"
                + " search_config_json, config_version, created_at, updated_at, verification_context_json)"
                + " VALUES (9702, 9002, 'q', 'q', '{}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1', 'replay', 9901,"
                + " 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1', now(), now(), '"
                + verificationContextJson + "'::jsonb)");
    }

    private void exec(String sql) {
        jdbc.execute(sql);
    }
}
