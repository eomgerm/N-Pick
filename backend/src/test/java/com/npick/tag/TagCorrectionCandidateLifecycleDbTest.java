package com.npick.tag;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @Test
    @Transactional
    @DisplayName("같은 본문을 다시 보내면 대기 근거를 재사용해 행이 늘지 않고 같은 id 를 돌려준다 (S15P21A501-317)")
    void resubmittingSameBodyReusesPendingEvidence() throws Exception {
        seed();

        List<String> first = postReplace(9901L, 2);
        List<String> second = postReplace(9901L, 0);

        assertThat(second).containsExactlyElementsOf(first);
        assertThat(pendingCount(9901L)).isEqualTo(2);
    }

    @Test
    @Transactional
    @DisplayName("재사용은 신고당 누적 상한(50)을 쓰지 않는다 — 상한이 찬 뒤에도 같은 본문 재전송은 성공하고 새 판단만 거부된다 (S15P21A501-317)")
    void reuseDoesNotConsumeAccumulatedLimit() throws Exception {
        seed();
        postReplace(9901L, 2);
        // 교체 본문과 무관한 태깅에 대기 판단 48건을 채워 누적 50 을 만든다 — 같은 태깅이면 반대 판단 정리에 지워진다.
        fillPending(9901L, 48);
        assertThat(pendingCount(9901L)).isEqualTo(50);

        postReplace(9901L, 0);

        mockMvc.perform(post("/api/v1/review/inquiries/9901/tag-correction-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[{\"action\":\"APPROVE\",\"scope\":\"CLIP\",\"tagType\":\"location\","
                                + "\"matchValue\":\"부산\",\"displayName\":\"부산\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TAG_409_003"));
    }

    @Test
    @Transactional
    @DisplayName("이미 확정된 근거는 재사용하지 않고 새 대기 근거를 만든다 (S15P21A501-317)")
    void confirmedEvidenceIsNotReused() throws Exception {
        seed();
        List<String> first = postReplace(9901L, 2);
        jdbc.update("UPDATE tag_evidence SET confirmed = true WHERE source_feedback_id = 9901");

        List<String> second = postReplace(9901L, 2);

        assertThat(second).doesNotContainAnyElementsOf(first);
        assertThat(pendingCount(9901L)).isEqualTo(2);
    }

    @Test
    @Transactional
    @DisplayName("다른 신고의 같은 대기 판단은 재사용하지 않는다 (S15P21A501-317)")
    void otherFeedbackPendingEvidenceIsIsolated() throws Exception {
        seed();
        // 같은 장면을 다른 검색에서 신고한 두 번째 신고. 한 검색 실행 안에서는 장면당 결과가 하나라 실행을 따로 둔다.
        jdbc.execute("INSERT INTO search_execution (search_execution_id, searched_by_id, query_text, normalized_query,"
                + " explicit_filters_json, normalized_filters_json, query_fingerprint, normalization_version,"
                + " execution_type, status, degraded_reasons_json, applied_excludes_json, search_config_json,"
                + " config_version, created_at, updated_at) VALUES (9702, 9002, 'q2', 'q2', '{}'::jsonb, '{}'::jsonb,"
                + " 'fp-9702', 'v1', 'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1',"
                + " now(), now())");
        jdbc.execute("INSERT INTO search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9802, 9702, 9301, 1, '{\"score\":1}'::jsonb)");
        jdbc.execute("INSERT INTO feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, created_at, review_started_at, updated_at) VALUES (9902, 9802, 9002,"
                + " 'REVIEWING', 9002, 'correction', now(), now(), now())");
        List<String> first = postReplace(9901L, 2);

        List<String> other = postReplace(9902L, 2);

        assertThat(other).doesNotContainAnyElementsOf(first);
        assertThat(pendingCount(9901L)).isEqualTo(2);
        assertThat(pendingCount(9902L)).isEqualTo(2);
    }

    @Test
    @Transactional
    @DisplayName("승인→반려→승인을 차례로 보내면 그 태깅에는 대기 승인 하나만 남는다 — 옛 반려가 최신으로 살아나지 않는다 (S15P21A501-317)")
    void approveRejectApproveLeavesSinglePendingApprove() throws Exception {
        seed();

        String firstApprove = postOps(9901L, op("APPROVE", "SCENE", "서울"), 1).get(0);
        postOps(9901L, op("REJECT", "SCENE", "서울"), 1);
        List<String> finalApprove = postOps(9901L, op("APPROVE", "SCENE", "서울"), 1);

        assertThat(pendingStatuses(9901L, "서울", true)).containsExactly("verified");
        assertThat(finalApprove).doesNotContain(firstApprove); // 첫 승인은 반려 때 지워졌다
    }

    @Test
    @Transactional
    @DisplayName("반려 후 승인을 보내면 대기 승인 하나만 남고, 다른 태깅의 대기 판단은 그대로다 (S15P21A501-317)")
    void rejectThenApproveKeepsOnlyApproveAndLeavesOtherTaggings() throws Exception {
        seed();
        postReplace(9901L, 2); // 장면 서울 REJECT + 장면 제주도 APPROVE
        postOps(9901L, op("REJECT", "CLIP", "서울"), 1); // 같은 값의 클립 태깅은 다른 태깅이다

        postOps(9901L, op("APPROVE", "SCENE", "서울"), 1);

        assertThat(pendingStatuses(9901L, "서울", true)).containsExactly("verified");
        assertThat(pendingStatuses(9901L, "서울", false)).containsExactly("rejected");
        assertThat(pendingStatuses(9901L, "제주도", true)).containsExactly("verified");
        assertThat(pendingCount(9901L)).isEqualTo(3);
    }

    @Test
    @Transactional
    @DisplayName("상한(50)이 찬 뒤에도 판단 뒤집기는 반대 판단을 지운 수로 세어 성공한다 (S15P21A501-317)")
    void flippingJudgmentAtLimitDoesNotExceed() throws Exception {
        seed();
        postReplace(9901L, 2);
        fillPending(9901L, 48);

        postOps(9901L, op("APPROVE", "SCENE", "서울"), 1);

        assertThat(pendingCount(9901L)).isEqualTo(50);
        assertThat(pendingStatuses(9901L, "서울", true)).containsExactly("verified");
    }

    private static String op(String action, String scope, String value) {
        return "{\"action\":\"" + action + "\",\"scope\":\"" + scope + "\",\"tagType\":\"location\",\"matchValue\":\""
                + value + "\",\"displayName\":\"" + value + "\"}";
    }

    private List<String> postOps(long feedbackId, String operation, int expectedNewlyCreated) throws Exception {
        String body = mockMvc.perform(post("/api/v1/review/inquiries/" + feedbackId + "/tag-correction-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[" + operation + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.newlyCreated").value(expectedNewlyCreated))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.data.evidenceIds");
    }

    private List<String> pendingStatuses(long feedbackId, String matchValue, boolean sceneScope) {
        return jdbc.queryForList(
                "SELECT te.verification_status FROM tag_evidence te JOIN tagging tg ON tg.tagging_id = te.tagging_id"
                        + " JOIN tag t ON t.tag_id = tg.tag_id WHERE te.source_feedback_id = ? AND te.confirmed = false"
                        + " AND te.source = 'reviewer_feedback' AND t.match_value = ? AND (tg.scene_id IS NOT NULL) = ?",
                String.class,
                feedbackId,
                matchValue,
                sceneScope);
    }

    // 요청과 무관한 태깅 하나에 같은 판단(withdrawn) 대기 근거를 count 건 넣는다.
    private void fillPending(long feedbackId, int count) {
        jdbc.execute(
                "INSERT INTO tag (tag_id, tag_type, match_value, name) VALUES (9499, 'keyword', 'filler', 'filler')"
                        + " ON CONFLICT DO NOTHING");
        jdbc.execute("INSERT INTO tagging (tagging_id, clip_id, scene_id, tag_id, created_at)"
                + " VALUES (9599, 9101, 9301, 9499, now()) ON CONFLICT DO NOTHING");
        jdbc.update(
                "INSERT INTO tag_evidence (evidence_id, tagging_id, source, confidence, verification_status,"
                        + " source_feedback_id, confirmed, created_at)"
                        + " SELECT 7000000 + g, 9599, 'reviewer_feedback', NULL, 'withdrawn', ?, false, now()"
                        + " FROM generate_series(1, ?) g",
                feedbackId,
                count);
    }

    private static final AuthenticatedMember REVIEWER = new AuthenticatedMember(9002L, "reviewer01", "h", "REVIEWER");

    private List<String> postReplace(long feedbackId, int expectedNewlyCreated) throws Exception {
        String body = mockMvc.perform(post("/api/v1/review/inquiries/" + feedbackId + "/tag-correction-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REPLACE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.newlyCreated").value(expectedNewlyCreated))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.data.evidenceIds");
    }

    private int pendingCount(long feedbackId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM tag_evidence WHERE source_feedback_id = ? AND confirmed = false"
                        + " AND source = 'reviewer_feedback'",
                Integer.class,
                feedbackId);
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
