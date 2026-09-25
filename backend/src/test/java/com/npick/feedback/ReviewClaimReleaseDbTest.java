package com.npick.feedback;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorCode;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.feedback.application.ClaimInquiryUseCase;
import com.npick.feedback.application.ConfirmCorrectionCommand;
import com.npick.feedback.application.ConfirmCorrectionUseCase;
import com.npick.feedback.application.ReleaseInquiryClaimUseCase;
import com.npick.feedback.application.error.ConfirmCorrectionErrorCode;
import com.npick.feedback.application.port.CurrentCorrectionStatePort;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * 검수 취소(claim 해제, S15P21A501-289)를 실제 PostgreSQL 로 검증한다. 대기 교정 후보 3종 폐기와 신고 초기화가 한 트랜잭션으로 일어나고, 확정·다른 검수자의 claim 과
 * 교정 상태 잠금·CAS 로 직렬화되는지 본다.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewClaimReleaseDbTest {

    private static final long FEEDBACK_ID = 9901L;
    private static final long REVIEWER_A = 9002L;
    private static final long REVIEWER_B = 9003L;
    private static final long EXECUTION_ID = 9702L;
    private static final String CONDITION = """
            {"syntax_version":"parse-rule/v1","resolution_schema_version":"query-resolver/v2",
             "all":[{"axis":"locations","op":"has_value","value":"○○공장"}]}
            """;
    private static final String PATCH = """
            {"syntax_version":"parse-rule/v1","operations":[
             {"op":"remove_item","axis":"locations","type":"location","value":"○○공장"}]}
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReleaseInquiryClaimUseCase releaseClaim;

    @Autowired
    private ClaimInquiryUseCase claimInquiry;

    @Autowired
    private ConfirmCorrectionUseCase confirmCorrection;

    @Autowired
    private CurrentCorrectionStatePort currentCorrectionState;

    @MockitoSpyBean
    private CorrectionStateLock correctionStateLock;

    @DynamicPropertySource
    static void provisionDatabase(DynamicPropertyRegistry properties) {
        String url = NpickPostgres.freshDatabase("npick_review_claim_release");
        NpickPostgres.migrate(url);
        NpickPostgres.datasource(properties, url);
    }

    @AfterEach
    void cleanup() {
        Mockito.reset(correctionStateLock);
        jdbc.execute("TRUNCATE TABLE npick.member, npick.tag CASCADE");
    }

    @Test
    @DisplayName("검수 취소는 대기 후보 3종을 폐기하고 신고를 open·담당자·판정·사유 없음으로 되돌리며, 이후 다른 검수자가 정상 claim 한다")
    void releaseDiscardsPendingCandidatesAndLetsAnotherReviewerClaim() {
        seed();

        releaseClaim.release(FEEDBACK_ID, REVIEWER_A);

        Map<String, Object> row = feedbackRow();
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("reviewed_by_id")).isNull();
        assertThat(row.get("review_started_at")).isNull();
        assertThat(row.get("resolution")).isNull();
        assertThat(row.get("resolution_note")).isNull();
        assertThat(pendingRuleCount()).isZero();
        assertThat(pendingEvidenceCount()).isZero();
        // 이 신고와 무관한 기존 AI 근거는 건드리지 않는다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.tag_evidence WHERE evidence_id = 7902", Integer.class))
                .isEqualTo(1);

        claimInquiry.claim(FEEDBACK_ID, REVIEWER_B);

        Map<String, Object> reclaimed = feedbackRow();
        assertThat(reclaimed.get("status")).isEqualTo("REVIEWING");
        assertThat(((Number) reclaimed.get("reviewed_by_id")).longValue()).isEqualTo(REVIEWER_B);
        assertThat(reclaimed.get("review_started_at")).isNotNull();
    }

    @Test
    @DisplayName("담당이 아닌 검수자의 검수 취소는 FEEDBACK_403_002 로 거부되고 신고·후보가 그대로 남는다")
    void nonAssignedReviewerCannotRelease() {
        seed();

        assertThat(releaseAttempt(REVIEWER_B)).isEqualTo(FeedbackErrorCode.NOT_REVIEWER);

        Map<String, Object> row = feedbackRow();
        assertThat(row.get("status")).isEqualTo("REVIEWING");
        assertThat(((Number) row.get("reviewed_by_id")).longValue()).isEqualTo(REVIEWER_A);
        assertThat(row.get("resolution")).isEqualTo("correction");
        assertThat(pendingRuleCount()).isEqualTo(2);
        assertThat(pendingEvidenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 해제된 문의를 다시 취소하면 FEEDBACK_409_003")
    void releasingTwiceConflicts() {
        seed();
        releaseClaim.release(FEEDBACK_ID, REVIEWER_A);

        assertThat(releaseAttempt(REVIEWER_A)).isEqualTo(FeedbackErrorCode.NOT_RESOLVABLE);
    }

    @Test
    @Timeout(15)
    @DisplayName("검수 취소가 잠금을 잡은 동안 확정은 대기하고, 이어서 담당자 없음으로 거부된다")
    void releaseFirstBlocksConfirmation() throws Exception {
        seed();
        seedReplay();
        LockGate gate = gateFirst("release-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ErrorCode> release = executor.submit(() -> named("release-first", () -> releaseAttempt(REVIEWER_A)));
            assertThat(gate.firstAcquired().await(5, TimeUnit.SECONDS)).isTrue();

            Future<ErrorCode> confirmation = executor.submit(() -> named("confirm-second", this::confirmAttempt));
            assertThat(gate.secondEntered().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(confirmation.isDone()).isFalse();

            gate.releaseFirst().countDown();

            assertThat(release.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(confirmation.get(5, TimeUnit.SECONDS)).isEqualTo(ConfirmCorrectionErrorCode.NOT_REVIEWER);
            assertThat(feedbackRow().get("status")).isEqualTo("OPEN");
            assertThat(pendingRuleCount()).isZero();
            assertThat(pendingEvidenceCount()).isZero();
            assertThat(activeRuleCount()).isZero();
        } finally {
            gate.releaseFirst().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(15)
    @DisplayName("확정이 잠금을 잡은 동안 검수 취소는 대기하고, 이어서 종료 상태를 보고 FEEDBACK_409_003 으로 거부된다")
    void confirmationFirstBlocksRelease() throws Exception {
        seed();
        seedReplay();
        LockGate gate = gateFirst("confirm-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ErrorCode> confirmation = executor.submit(() -> named("confirm-first", this::confirmAttempt));
            assertThat(gate.firstAcquired().await(5, TimeUnit.SECONDS)).isTrue();

            Future<ErrorCode> release =
                    executor.submit(() -> named("release-second", () -> releaseAttempt(REVIEWER_A)));
            assertThat(gate.secondEntered().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(release.isDone()).isFalse();

            gate.releaseFirst().countDown();

            assertThat(confirmation.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(release.get(5, TimeUnit.SECONDS)).isEqualTo(FeedbackErrorCode.NOT_RESOLVABLE);
            Map<String, Object> row = feedbackRow();
            assertThat(row.get("status")).isEqualTo("CLOSED");
            assertThat(((Number) row.get("reviewed_by_id")).longValue()).isEqualTo(REVIEWER_A);
            // 확정된 후보는 취소가 지우지 않는다.
            assertThat(activeRuleCount()).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM npick.tag_evidence WHERE source_feedback_id = ? AND confirmed = true",
                            Integer.class,
                            FEEDBACK_ID))
                    .isEqualTo(1);
        } finally {
            gate.releaseFirst().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(15)
    @DisplayName("검수 취소 도중 다른 검수자의 claim 은 CAS 로 지고(FEEDBACK_409_001), 취소 커밋 뒤에는 claim 이 성공한다")
    void claimDuringReleaseLosesThenSucceedsAfterCommit() throws Exception {
        seed();
        LockGate gate = gateFirst("release-first");
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Future<ErrorCode> release = executor.submit(() -> named("release-first", () -> releaseAttempt(REVIEWER_A)));
            assertThat(gate.firstAcquired().await(5, TimeUnit.SECONDS)).isTrue();

            // claim 은 교정 상태 잠금을 쓰지 않고 status='OPEN' CAS 만 건다. 취소가 커밋되기 전이라 아직 REVIEWING 이다.
            assertThat(claimAttempt(REVIEWER_B)).isEqualTo(FeedbackErrorCode.ALREADY_CLAIMED);

            gate.releaseFirst().countDown();
            assertThat(release.get(5, TimeUnit.SECONDS)).isNull();

            assertThat(claimAttempt(REVIEWER_B)).isNull();
            assertThat(((Number) feedbackRow().get("reviewed_by_id")).longValue()).isEqualTo(REVIEWER_B);
        } finally {
            gate.releaseFirst().countDown();
            executor.shutdownNow();
        }
    }

    private LockGate gateFirst(String firstThreadName) {
        LockGate gate = new LockGate(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        doAnswer(invocation -> {
                    if (firstThreadName.equals(Thread.currentThread().getName())) {
                        Object result = invocation.callRealMethod();
                        gate.firstAcquired().countDown();
                        if (!gate.releaseFirst().await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out while holding correction state lock");
                        }
                        return result;
                    }
                    gate.secondEntered().countDown();
                    return invocation.callRealMethod();
                })
                .when(correctionStateLock)
                .acquire();
        return gate;
    }

    private ErrorCode releaseAttempt(long reviewerId) {
        try {
            releaseClaim.release(FEEDBACK_ID, reviewerId);
            return null;
        } catch (BusinessException e) {
            return e.errorCode();
        }
    }

    private ErrorCode claimAttempt(long reviewerId) {
        try {
            claimInquiry.claim(FEEDBACK_ID, reviewerId);
            return null;
        } catch (BusinessException e) {
            return e.errorCode();
        }
    }

    private ErrorCode confirmAttempt() {
        try {
            confirmCorrection.confirm(new ConfirmCorrectionCommand(FEEDBACK_ID, REVIEWER_A, true, EXECUTION_ID));
            return null;
        } catch (BusinessException e) {
            return e.errorCode();
        }
    }

    private <T> T named(String name, ThrowingSupplier<T> action) throws Exception {
        Thread.currentThread().setName(name);
        return action.get();
    }

    /** 검수자 A 가 correction 판정으로 잡은 신고에 대기 후보 3종(patch_parse·exclude_scene·태그 근거)이 있는 상태. */
    private void seed() {
        jdbc.update("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-release', 'h', '편집기자', 'editor', now(), now()),"
                + " (9002, 'reviewer-release-a', 'h', '검수자A', 'reviewer', now(), now()),"
                + " (9003, 'reviewer-release-b', 'h', '검수자B', 'reviewer', now(), now())");
        jdbc.update("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, active_pipeline_run_id, created_at, updated_at) VALUES"
                + " (9101, 'broadcast', 'clips/release/o', repeat('a', 64), 'none', 9001, NULL, now(), now())");
        jdbc.update("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at) VALUES"
                + " (9201, 9101, 1, 'v1', 'succeeded', '{}'::jsonb, now(), now())");
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id = 9201 WHERE clip_id = 9101");
        jdbc.update(
                "INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                        + " created_at, updated_at) VALUES (9301, 9101, 9201, 0, 1000, 'b_roll', now(), now())");
        jdbc.update("INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text,"
                + " normalized_query, explicit_filters_json, normalized_filters_json, query_fingerprint,"
                + " normalization_version, execution_type, status, degraded_reasons_json, applied_excludes_json,"
                + " search_config_json, config_version, resolver_output_json, created_at, updated_at) VALUES"
                + " (9701, 9001, 'q', 'q', '{}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1', 'original', 'succeeded',"
                + " '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1',"
                + " '{\"schema_version\":\"query-resolver/v2\",\"intent\":\"scene_search\"}'::jsonb, now(), now())");
        jdbc.update("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{\"score\":1}'::jsonb)");
        jdbc.update("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, resolution_note, created_at, review_started_at, updated_at) VALUES"
                + " (9901, 9801, 9001, 'REVIEWING', 9002, 'correction', '해석과 태그 모두 틀림', now(), now(), now())");
        jdbc.update(
                "INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                        + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                        + " active, created_at, updated_at, condition_json, patch_json, request_key) VALUES"
                        + " (6601, 'fp-9701', 'q', '{}'::jsonb, 'v1', 'patch_parse', NULL, 9901, false, now(), now(),"
                        + " CAST(? AS jsonb), CAST(? AS jsonb), 'pending-patch')",
                CONDITION,
                PATCH);
        jdbc.update("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at, request_key) VALUES"
                + " (6602, 'fp-9701', 'q', '{}'::jsonb, 'v1', 'exclude_scene', 9301, 9901, false, now(), now(),"
                + " 'pending-exclude')");
        jdbc.update("INSERT INTO npick.tag (tag_id, tag_type, match_value, name)"
                + " VALUES (7701, 'location', '제주도', '제주도')");
        jdbc.update("INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)"
                + " VALUES (7801, 9101, 9301, 7701, now())");
        jdbc.update("INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence, verification_status,"
                + " source_feedback_id, confirmed, created_at) VALUES"
                + " (7901, 7801, 'reviewer_feedback', NULL, 'verified', 9901, false, now()),"
                + " (7902, 7801, 'vlm', 0.9, 'unverified', NULL, true, now())");
    }

    /** 현재 교정 상태 지문으로 3종 후보를 모두 승인한 검증 재검색 실행. */
    private void seedReplay() {
        String context = "{\"resolution\":\"correction\",\"approved_evidence_ids\":[7901],"
                + "\"candidate_rules\":[{\"approved_rule_id\":6601,\"replaced_rule_id\":null,\"action\":\"patch_parse\"},"
                + "{\"approved_rule_id\":6602,\"replaced_rule_id\":null,\"action\":\"exclude_scene\"}],"
                + "\"state_fingerprint\":\"" + currentCorrectionState.currentFingerprint(FEEDBACK_ID) + "\"}";
        jdbc.update(
                "INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text,"
                        + " normalized_query, explicit_filters_json, normalized_filters_json, query_fingerprint,"
                        + " normalization_version, execution_type, replay_of_feedback_id, status, degraded_reasons_json,"
                        + " applied_excludes_json, search_config_json, config_version, verification_context_json,"
                        + " created_at, updated_at) VALUES"
                        + " (9702, 9002, 'q', 'q', '{}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1', 'replay', 9901, 'succeeded',"
                        + " '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1', CAST(? AS jsonb), now(), now())",
                context);
    }

    private Map<String, Object> feedbackRow() {
        return jdbc.queryForMap(
                "SELECT status, reviewed_by_id, review_started_at, resolution, resolution_note"
                        + " FROM npick.feedback WHERE feedback_id = ?",
                FEEDBACK_ID);
    }

    private int pendingRuleCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM npick.search_rule WHERE source_feedback_id = ? AND active = false",
                Integer.class,
                FEEDBACK_ID);
    }

    private int activeRuleCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM npick.search_rule WHERE source_feedback_id = ? AND active = true",
                Integer.class,
                FEEDBACK_ID);
    }

    private int pendingEvidenceCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM npick.tag_evidence WHERE source_feedback_id = ? AND confirmed = false",
                Integer.class,
                FEEDBACK_ID);
    }

    private record LockGate(CountDownLatch firstAcquired, CountDownLatch secondEntered, CountDownLatch releaseFirst) {}

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
