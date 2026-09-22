package com.npick.feedback;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import com.npick.feedback.application.ConfirmCorrectionCommand;
import com.npick.feedback.application.ConfirmCorrectionUseCase;
import com.npick.feedback.application.ResolveInquiryUseCase;
import com.npick.feedback.application.error.ConfirmCorrectionErrorCode;
import com.npick.feedback.application.port.CurrentCorrectionStatePort;
import com.npick.search.application.CreateParsePatchCandidateCommand;
import com.npick.search.application.CreateParsePatchCandidateUseCase;
import com.npick.search.application.CreateSceneExcludeCandidateCommand;
import com.npick.search.application.CreateSceneExcludeCandidateUseCase;
import com.npick.search.application.error.ParseRuleCandidateErrorCode;
import com.npick.search.application.error.SceneExcludeCandidateErrorCode;
import com.npick.support.NpickPostgres;
import com.npick.tag.application.CreateTagCorrectionCandidateCommand;
import com.npick.tag.application.CreateTagCorrectionCandidateUseCase;
import com.npick.tag.application.TagCorrectionAction;
import com.npick.tag.application.TagOperation;
import com.npick.tag.application.TagScope;
import com.npick.tag.application.error.TagCorrectionCandidateErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/** 실제 PostgreSQL advisory lock 으로 확정과 세 후보 생성 경로가 직렬화되는지 검증한다(S15P21A501-220). */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CorrectionCandidateConcurrencyDbTest {

    private static final long FEEDBACK_ID = 9901L;
    private static final long REVIEWER_ID = 9002L;
    private static final long EXECUTION_ID = 9702L;
    private static final long SCENE_ID = 9301L;
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
    private ConfirmCorrectionUseCase confirmCorrection;

    @Autowired
    private ResolveInquiryUseCase resolveInquiry;

    @Autowired
    private CreateParsePatchCandidateUseCase createParsePatch;

    @Autowired
    private CreateSceneExcludeCandidateUseCase createSceneExclude;

    @Autowired
    private CreateTagCorrectionCandidateUseCase createTagCorrection;

    @Autowired
    private CurrentCorrectionStatePort currentCorrectionState;

    @MockitoSpyBean
    private CorrectionStateLock correctionStateLock;

    @DynamicPropertySource
    static void provisionDatabase(DynamicPropertyRegistry properties) {
        String url = NpickPostgres.freshDatabase("npick_correction_candidate_race");
        NpickPostgres.migrate(url);
        NpickPostgres.datasource(properties, url);
    }

    @AfterEach
    void cleanup() {
        Mockito.reset(correctionStateLock);
        jdbc.execute("TRUNCATE TABLE npick.member, npick.tag CASCADE");
    }

    @ParameterizedTest(name = "{0}: 확정이 먼저 잠금을 잡으면 후보 생성은 종료 상태를 다시 읽고 거부된다")
    @EnumSource(CandidatePath.class)
    @Timeout(15)
    void confirmationFirstRejectsEveryCandidatePath(CandidatePath path) throws Exception {
        seed(path);
        LockGate gate = gateFirst("confirm-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ErrorCode> confirmation = executor.submit(() -> named("confirm-first", this::confirmAttempt));
            assertThat(gate.firstAcquired().await(5, TimeUnit.SECONDS)).isTrue();

            Future<ErrorCode> candidate =
                    executor.submit(() -> named("candidate-second", () -> candidateAttempt(path)));
            assertThat(gate.secondEntered().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(candidate.isDone()).isFalse();

            gate.releaseFirst().countDown();

            assertThat(confirmation.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(candidate.get(5, TimeUnit.SECONDS)).isEqualTo(path.notReviewingError());
            assertThat(feedbackStatus()).isEqualTo("CLOSED");
            assertThat(candidateCount(path)).isEqualTo(1);
        } finally {
            gate.releaseFirst().countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}: 종료 판정이 먼저 잠금을 잡으면 후보 생성은 종료 상태를 다시 읽고 거부된다")
    @EnumSource(CandidatePath.class)
    @Timeout(15)
    void terminalResolutionFirstRejectsEveryCandidatePath(CandidatePath path) throws Exception {
        seedForCandidateCreation(path);
        LockGate gate = gateFirst("resolve-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ErrorCode> resolution =
                    executor.submit(() -> named("resolve-first", () -> resolveAttempt("no_action", "조치 불필요")));
            assertThat(gate.firstAcquired().await(5, TimeUnit.SECONDS)).isTrue();

            Future<ErrorCode> candidate =
                    executor.submit(() -> named("candidate-second", () -> candidateAttempt(path)));
            assertThat(gate.secondEntered().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(candidate.isDone()).isFalse();

            gate.releaseFirst().countDown();

            assertThat(resolution.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(candidate.get(5, TimeUnit.SECONDS)).isEqualTo(path.notReviewingError());
            assertThat(feedbackStatus()).isEqualTo("CLOSED");
            assertThat(feedbackResolution()).isEqualTo("no_action");
            assertThat(candidateCount(path)).isZero();
        } finally {
            gate.releaseFirst().countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}: 다른 교정 판정이 먼저 잠금을 잡으면 후보 생성은 새 판정을 다시 읽고 거부된다")
    @EnumSource(CandidatePath.class)
    @Timeout(15)
    void correctionResolutionFirstRejectsCandidateForPreviousResolution(CandidatePath path) throws Exception {
        seedForCandidateCreation(path);
        LockGate gate = gateFirst("resolve-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ErrorCode> resolution = executor.submit(
                    () -> named("resolve-first", () -> resolveAttempt(path.incompatibleResolution(), null)));
            assertThat(gate.firstAcquired().await(5, TimeUnit.SECONDS)).isTrue();

            Future<ErrorCode> candidate =
                    executor.submit(() -> named("candidate-second", () -> candidateAttempt(path)));
            assertThat(gate.secondEntered().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(candidate.isDone()).isFalse();

            gate.releaseFirst().countDown();

            assertThat(resolution.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(candidate.get(5, TimeUnit.SECONDS)).isEqualTo(path.wrongResolutionError());
            assertThat(feedbackStatus()).isEqualTo("REVIEWING");
            assertThat(feedbackResolution()).isEqualTo(path.incompatibleResolution());
            assertThat(candidateCount(path)).isZero();
        } finally {
            gate.releaseFirst().countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}: 후보 생성이 먼저 잠금을 잡으면 판정 변경이 후보 커밋까지 대기한다")
    @EnumSource(CandidatePath.class)
    @Timeout(15)
    void candidateFirstBlocksResolutionChangeUntilCandidateCommits(CandidatePath path) throws Exception {
        seedForCandidateCreation(path);
        LockGate gate = gateFirst("candidate-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ErrorCode> candidate = executor.submit(() -> named("candidate-first", () -> candidateAttempt(path)));
            assertThat(gate.firstAcquired().await(5, TimeUnit.SECONDS)).isTrue();

            Future<ErrorCode> resolution =
                    executor.submit(() -> named("resolve-second", () -> resolveAttempt("no_action", "조치 불필요")));
            assertThat(gate.secondEntered().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(resolution.isDone()).isFalse();

            gate.releaseFirst().countDown();

            assertThat(candidate.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(resolution.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(candidateCount(path)).isEqualTo(1);
            assertThat(feedbackStatus()).isEqualTo("CLOSED");
            assertThat(feedbackResolution()).isEqualTo("no_action");
        } finally {
            gate.releaseFirst().countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}: 새 후보가 먼저 저장되면 확정은 지문 변경으로 재검증을 요구한다")
    @EnumSource(
            value = CandidatePath.class,
            names = {"PATCH_PARSE", "TAG_CORRECTION"})
    @Timeout(15)
    void newCandidateFirstInvalidatesStaleVerification(CandidatePath path) throws Exception {
        seed(path);
        LockGate gate = gateFirst("candidate-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ErrorCode> candidate = executor.submit(() -> named("candidate-first", () -> candidateAttempt(path)));
            assertThat(gate.firstAcquired().await(5, TimeUnit.SECONDS)).isTrue();

            Future<ErrorCode> confirmation = executor.submit(() -> named("confirm-second", this::confirmAttempt));
            assertThat(gate.secondEntered().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(confirmation.isDone()).isFalse();

            gate.releaseFirst().countDown();

            assertThat(candidate.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(confirmation.get(5, TimeUnit.SECONDS))
                    .isEqualTo(ConfirmCorrectionErrorCode.NEEDS_REVERIFICATION);
            assertThat(feedbackStatus()).isEqualTo("REVIEWING");
            assertThat(candidateCount(path)).isEqualTo(2);
        } finally {
            gate.releaseFirst().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("exclude_scene 기존 후보 재요청은 잠금 안에서도 새 행 없이 멱등 결과를 유지한다")
    void excludeCandidateRetryRemainsIdempotent() {
        seed(CandidatePath.EXCLUDE_SCENE);

        assertThat(candidateAttempt(CandidatePath.EXCLUDE_SCENE)).isNull();

        assertThat(candidateCount(CandidatePath.EXCLUDE_SCENE)).isEqualTo(1);
        assertThat(feedbackStatus()).isEqualTo("REVIEWING");
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

    private ErrorCode candidateAttempt(CandidatePath path) {
        try {
            switch (path) {
                case PATCH_PARSE ->
                    createParsePatch.create(new CreateParsePatchCandidateCommand(
                            FEEDBACK_ID, REVIEWER_ID, true, "race-patch", CONDITION, PATCH, null));
                case EXCLUDE_SCENE ->
                    createSceneExclude.create(new CreateSceneExcludeCandidateCommand(
                            FEEDBACK_ID, REVIEWER_ID, true, "race-exclude", SCENE_ID));
                case TAG_CORRECTION ->
                    createTagCorrection.create(new CreateTagCorrectionCandidateCommand(
                            FEEDBACK_ID,
                            REVIEWER_ID,
                            true,
                            List.of(new TagOperation(
                                    TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "서울", "서울"))));
            }
            return null;
        } catch (BusinessException e) {
            return e.errorCode();
        }
    }

    private ErrorCode confirmAttempt() {
        try {
            confirmCorrection.confirm(new ConfirmCorrectionCommand(FEEDBACK_ID, REVIEWER_ID, true, EXECUTION_ID));
            return null;
        } catch (BusinessException e) {
            return e.errorCode();
        }
    }

    private ErrorCode resolveAttempt(String resolution, String note) {
        try {
            resolveInquiry.resolve(FEEDBACK_ID, REVIEWER_ID, resolution, note);
            return null;
        } catch (BusinessException e) {
            return e.errorCode();
        }
    }

    private <T> T named(String name, ThrowingSupplier<T> action) throws Exception {
        Thread.currentThread().setName(name);
        return action.get();
    }

    private void seed(CandidatePath path) {
        seedCommon(path.resolution());
        switch (path) {
            case PATCH_PARSE -> seedParseCandidate();
            case EXCLUDE_SCENE -> seedExcludeCandidate();
            case TAG_CORRECTION -> seedTagCandidate();
        }
        seedReplay(path, currentCorrectionState.currentFingerprint(FEEDBACK_ID));
    }

    private void seedForCandidateCreation(CandidatePath path) {
        seedCommon(path.resolution());
    }

    private void seedCommon(String resolution) {
        jdbc.update("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-race', 'h', '편집기자', 'editor', now(), now()),"
                + " (9002, 'reviewer-race', 'h', '검수자', 'reviewer', now(), now())");
        jdbc.update("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, active_pipeline_run_id, created_at, updated_at) VALUES"
                + " (9101, 'broadcast', 'clips/race/o', repeat('a', 64), 'none', 9001, NULL, now(), now())");
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
        jdbc.update(
                "INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                        + " resolution, created_at, review_started_at, updated_at) VALUES"
                        + " (9901, 9801, 9001, 'REVIEWING', 9002, ?, now(), now(), now())",
                resolution);
    }

    private void seedParseCandidate() {
        jdbc.update(
                "INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                        + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                        + " active, created_at, updated_at, condition_json, patch_json, request_key) VALUES"
                        + " (6601, 'fp-9701', 'q', '{}'::jsonb, 'v1', 'patch_parse', NULL, 9901, false, now(), now(),"
                        + " CAST(? AS jsonb), CAST(? AS jsonb), 'verified-patch')",
                CONDITION,
                PATCH);
    }

    private void seedExcludeCandidate() {
        jdbc.update("INSERT INTO npick.search_rule (search_rule_id, query_fingerprint, normalized_query,"
                + " normalized_filters_json, normalization_version, action, target_scene_id, source_feedback_id,"
                + " active, created_at, updated_at, request_key) VALUES"
                + " (6601, 'fp-9701', 'q', '{}'::jsonb, 'v1', 'exclude_scene', 9301, 9901, false, now(), now(),"
                + " 'verified-exclude')");
    }

    private void seedTagCandidate() {
        jdbc.update("INSERT INTO npick.tag (tag_id, tag_type, match_value, name)"
                + " VALUES (7701, 'location', '제주도', '제주도')");
        jdbc.update("INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)"
                + " VALUES (7801, 9101, 9301, 7701, now())");
        jdbc.update("INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence, verification_status,"
                + " source_feedback_id, confirmed, created_at) VALUES"
                + " (7901, 7801, 'reviewer_feedback', NULL, 'verified', 9901, false, now())");
    }

    private void seedReplay(CandidatePath path, String fingerprint) {
        String context =
                switch (path) {
                    case PATCH_PARSE ->
                        "{\"resolution\":\"patch_parse\",\"approved_evidence_ids\":[],"
                                + "\"approved_rule_id\":6601,\"replaced_rule_id\":null,\"state_fingerprint\":\""
                                + fingerprint + "\"}";
                    case EXCLUDE_SCENE ->
                        "{\"resolution\":\"exclude_scene\",\"approved_evidence_ids\":[],"
                                + "\"approved_rule_id\":6601,\"replaced_rule_id\":null,\"state_fingerprint\":\""
                                + fingerprint + "\"}";
                    case TAG_CORRECTION ->
                        "{\"resolution\":\"tag_correction\",\"approved_evidence_ids\":[7901],"
                                + "\"approved_rule_id\":null,\"replaced_rule_id\":null,\"state_fingerprint\":\""
                                + fingerprint + "\"}";
                };
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

    private int candidateCount(CandidatePath path) {
        String sql = path == CandidatePath.TAG_CORRECTION
                ? "SELECT count(*) FROM npick.tag_evidence WHERE source_feedback_id = 9901"
                : "SELECT count(*) FROM npick.search_rule WHERE source_feedback_id = 9901";
        return jdbc.queryForObject(sql, Integer.class);
    }

    private String feedbackStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM npick.feedback WHERE feedback_id = ?", String.class, FEEDBACK_ID);
    }

    private String feedbackResolution() {
        return jdbc.queryForObject(
                "SELECT resolution FROM npick.feedback WHERE feedback_id = ?", String.class, FEEDBACK_ID);
    }

    private enum CandidatePath {
        PATCH_PARSE(
                "patch_parse",
                "exclude_scene",
                ParseRuleCandidateErrorCode.NOT_REVIEWING,
                ParseRuleCandidateErrorCode.NOT_PATCH_PARSE),
        EXCLUDE_SCENE(
                "exclude_scene",
                "patch_parse",
                SceneExcludeCandidateErrorCode.NOT_REVIEWING,
                SceneExcludeCandidateErrorCode.NOT_EXCLUDE_SCENE),
        TAG_CORRECTION(
                "tag_correction",
                "exclude_scene",
                TagCorrectionCandidateErrorCode.NOT_REVIEWING,
                TagCorrectionCandidateErrorCode.NOT_TAG_CORRECTION);

        private final String resolution;
        private final String incompatibleResolution;
        private final ErrorCode notReviewingError;
        private final ErrorCode wrongResolutionError;

        CandidatePath(
                String resolution,
                String incompatibleResolution,
                ErrorCode notReviewingError,
                ErrorCode wrongResolutionError) {
            this.resolution = resolution;
            this.incompatibleResolution = incompatibleResolution;
            this.notReviewingError = notReviewingError;
            this.wrongResolutionError = wrongResolutionError;
        }

        String resolution() {
            return resolution;
        }

        ErrorCode notReviewingError() {
            return notReviewingError;
        }

        String incompatibleResolution() {
            return incompatibleResolution;
        }

        ErrorCode wrongResolutionError() {
            return wrongResolutionError;
        }
    }

    private record LockGate(CountDownLatch firstAcquired, CountDownLatch secondEntered, CountDownLatch releaseFirst) {}

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
