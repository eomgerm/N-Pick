package com.npick.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.npick.clip.application.command.ClipPublicationService;
import com.npick.clip.infrastructure.config.ClipRegistrationProperties;
import com.npick.clip.infrastructure.persistence.repository.JdbcClipPublicationAdapter;
import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.command.StageExecutionService;
import com.npick.pipeline.application.command.claim.ClaimStageCommand;
import com.npick.pipeline.application.command.complete.CompleteStageCommand;
import com.npick.pipeline.application.port.StageOutputPort;
import com.npick.pipeline.application.query.definition.GetPipelineDefinitionUseCase;
import com.npick.pipeline.domain.model.JsonValues;
import com.npick.pipeline.domain.model.PipelineStages;
import com.npick.pipeline.infrastructure.json.JobJsonAdapter;
import com.npick.pipeline.infrastructure.persistence.JdbcPipelineRunRepository;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** mock 결과만 생성하고 배정·수락·DB·활성화는 실제 구현을 사용한다. 실제 AI 왕복 테스트가 아니다. */
class StageExecutionIntegrationTest {
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactions;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JobJsonAdapter HASH = new JobJsonAdapter();
    private static final Map<String, String> VERSIONS = versions();
    private static final String VERSION =
            "npick-pipeline/v1:" + HASH.hash(VERSIONS).substring(0, 12);
    private static long sequence = 10000;

    @TempDir
    Path media;

    private StageExecutionService executor;
    private MutableClock clock;
    private MockOutputs outputs;

    @BeforeAll
    static void database() {
        String url = NpickPostgres.freshDatabase("npick_stage_executor");
        NpickPostgres.migrate(url);
        var dataSource = new DriverManagerDataSource(url, NpickPostgres.username(), NpickPostgres.password());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jdbc.update("INSERT INTO npick.member VALUES (1,'stage-reviewer','test-only','검수자','reviewer',now(),now())");
    }

    @BeforeEach
    void setUp() {
        configure(com.npick.pipeline.domain.model.StageRetrySettings.disabled());
    }

    private void configure(com.npick.pipeline.domain.model.StageRetrySettings retries) {
        jdbc.update(
                "UPDATE npick.pipeline_run SET status='failed', lease_id=NULL, lease_stage=NULL, lease_worker_id=NULL, lease_expires_at=NULL, lease_heartbeat_at=NULL WHERE status IN ('queued','running')");
        clock = new MutableClock();
        outputs = new MockOutputs();
        executor = newExecutor(retries);
    }

    private StageExecutionService newExecutor(com.npick.pipeline.domain.model.StageRetrySettings retries) {
        var properties =
                new ClipRegistrationProperties(media, media.resolve("upload"), null, null, null, false, 10485760);
        var publication = new ClipPublicationService(new JdbcClipPublicationAdapter(jdbc, properties));
        return transactional(new StageExecutionService(
                new JdbcPipelineRunRepository(jdbc, JSON),
                () -> new GetPipelineDefinitionUseCase.Definition(VERSION, PipelineStages.NAMES, VERSIONS),
                outputs,
                HASH,
                clock,
                publication,
                retries));
    }

    @Test
    void retryBudgetPreservesSuccessfulStagesAndActiveResults() throws Exception {
        var budgets = new LinkedHashMap<String, Integer>();
        PipelineStages.NAMES.forEach(stage -> budgets.put(stage, 2));
        configure(new com.npick.pipeline.domain.model.StageRetrySettings(
                budgets, com.npick.pipeline.domain.model.StageRetrySettings.TRANSIENT_CODES));
        long run = createRun(null, 1, true);
        for (String stage : PipelineStages.NAMES) {
            var first = claim();
            assertThat(object(first.get("job"))).containsEntry("stage", stage);
            if (stage.equals("scene_detection") || stage.equals("asr")) {
                var failed = success(first);
                failBody(failed, "failed", stage.equals("asr") ? "ASR_FAILED" : "SCENE_DETECTION_FAILED");
                complete(run, stage, failed);
                assertThat(state(run, stage)).containsEntry("status", "pending").containsEntry("attempts", 1);
                assertThat(complete(run, stage, failed)).containsEntry("duplicate", true);
                var second = claim();
                assertThat(object(second.get("job")))
                        .containsEntry("attempt", 2)
                        .containsEntry("maxAttempts", 2)
                        .containsEntry("outputKeyPrefix", "runs/" + run + "/" + stage + "/a2/")
                        .containsEntry("idempotencyKey", run + ":" + stage + ":2");
                assertThat(executor.reserve(worker())).isEmpty();
                var lateSuccess = success(first);
                rejects(() -> complete(run, stage, lateSuccess), "JOB_409_003");
                var result = success(second);
                if (stage.equals("asr")) failBody(result, "failed", "ASR_FAILED");
                complete(run, stage, result);
                assertThat(complete(run, stage, result)).containsEntry("duplicate", true);
                rejects(() -> complete(run, stage, lateSuccess), "JOB_409_003");
            } else complete(run, stage, success(first));
        }
        assertThat(row(run)).containsEntry("status", "succeeded");
        assertThat(active(run)).isEqualTo(run);
        assertThat(sceneCount(run)).isEqualTo(1);
        assertThat(state(run, "asr"))
                .containsEntry("status", "failed")
                .containsEntry("attempts", 2)
                .containsEntry("errorCode", "ASR_FAILED")
                .containsEntry("retryScheduled", false);
        assertThat((List<?>) state(run, "scene_detection").get("failedAttempts"))
                .hasSize(1);
        for (String stage : PipelineStages.NAMES)
            if (!List.of("scene_detection", "asr").contains(stage))
                assertThat(state(run, stage))
                        .containsEntry("status", "succeeded")
                        .containsEntry("attempts", 1);

        long replacement = createRun(((Number) row(run).get("clip_id")).longValue(), 2, true);
        for (int attempt = 1; attempt <= 2; attempt++) {
            var failed = success(claim());
            failBody(failed, "failed", "SCENE_DETECTION_FAILED");
            complete(replacement, "scene_detection", failed);
        }
        assertThat(row(replacement)).containsEntry("status", "failed");
        assertThat(active(replacement)).isEqualTo(run);
        assertThat(sceneCount(replacement)).isZero();
        assertThat(executor.reserve(worker())).isEmpty();
    }

    @Test
    void reclaimedRetryLeaseDoesNotSpendAnotherAttempt() throws Exception {
        configure(new com.npick.pipeline.domain.model.StageRetrySettings(
                Map.of("scene_detection", 2), java.util.Set.of("SCENE_DETECTION_FAILED")));
        long run = createRun(null, 1, true);
        var failed = success(claim());
        failBody(failed, "failed", "SCENE_DETECTION_FAILED");
        complete(run, "scene_detection", failed);
        var old = success(claim());
        clock.advance(76);
        assertThat(executor.reclaim()).isEqualTo(1);
        var replacement = claim();
        assertThat(object(replacement.get("job"))).containsEntry("attempt", 2);
        rejects(() -> complete(run, "scene_detection", old), "JOB_409_002");
        complete(run, "scene_detection", success(replacement));
        assertThat(sceneCount(run)).isEqualTo(1);
    }

    @Test
    void acceptedAttemptReplaysAcrossWorkersCompletionAndRestartWithoutWritingAgain() throws Exception {
        configure(new com.npick.pipeline.domain.model.StageRetrySettings(
                Map.of("scene_detection", 2), java.util.Set.of("SCENE_DETECTION_FAILED")));
        long run = createRun(null, 1, true);
        var first = success(claim());
        failBody(first, "failed", "SCENE_DETECTION_FAILED");
        var original = complete(run, "scene_detection", first);
        var expected = new LinkedHashMap<>(original);
        expected.put("duplicate", true);
        var second = executor.reserve(new ClaimStageCommand("replacement", VERSIONS, Map.of()))
                .orElseThrow();
        assertThat(complete(run, "scene_detection", first)).isEqualTo(expected);
        var changed = new LinkedHashMap<>(first);
        changed.put("metrics", Map.of("changed", true));
        rejects(() -> complete(run, "scene_detection", changed), "JOB_409_003");
        rejects(
                () -> executor.complete(new CompleteStageCommand(
                        run, "scene_detection", "replacement", (String) first.get("idempotencyKey"), first)),
                "JOB_409_002");
        var result = success(second);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var replay = pool.submit(() -> complete(run, "scene_detection", first));
            var accepted = pool.submit(() -> executor.complete(new CompleteStageCommand(
                    run, "scene_detection", "replacement", (String) result.get("idempotencyKey"), result)));
            assertThat(replay.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(accepted.get(10, TimeUnit.SECONDS)).containsEntry("duplicate", false);
        }
        clock.advance(120);
        executor = newExecutor(com.npick.pipeline.domain.model.StageRetrySettings.disabled());
        assertThat(complete(run, "scene_detection", first)).isEqualTo(expected);
        rejects(() -> complete(run, "scene_detection", changed), "JOB_409_003");
        assertThat(sceneCount(run)).isEqualTo(1);
        assertThat(state(run, "scene_detection"))
                .containsEntry("status", "succeeded")
                .containsEntry("attempts", 2);
        assertThat(object(state(run, "scene_detection").get("completions"))).hasSize(2);
    }

    @Test
    void reservedRetryKeepsItsBudgetAndClassificationAfterSettingsChangeAndLeaseReclaim() throws Exception {
        configure(new com.npick.pipeline.domain.model.StageRetrySettings(
                Map.of("scene_detection", 3), java.util.Set.of("SCENE_DETECTION_FAILED")));
        long run = createRun(null, 1, true);
        var failed = success(claim());
        failBody(failed, "failed", "SCENE_DETECTION_FAILED");
        complete(run, "scene_detection", failed);
        executor = newExecutor(com.npick.pipeline.domain.model.StageRetrySettings.disabled());
        var second = claim();
        assertThat(object(second.get("job"))).containsEntry("attempt", 2).containsEntry("maxAttempts", 3);
        clock.advance(76);
        assertThat(executor.reclaim()).isEqualTo(1);
        var replacement = claim();
        assertThat(object(replacement.get("job"))).containsEntry("attempt", 2).containsEntry("maxAttempts", 3);
        var failedAgain = success(replacement);
        failBody(failedAgain, "failed", "SCENE_DETECTION_FAILED");
        complete(run, "scene_detection", failedAgain);
        assertThat(state(run, "scene_detection")).containsEntry("status", "pending");
        var third = claim();
        assertThat(object(third.get("job"))).containsEntry("attempt", 3).containsEntry("maxAttempts", 3);
        complete(run, "scene_detection", success(third));
        assertThat(object(claim().get("job")))
                .containsEntry("stage", "frame_extraction")
                .containsEntry("maxAttempts", 1);
    }

    @Test
    void enablingRetriesDoesNotExpandAnAlreadyAssignedBudget() throws Exception {
        long run = createRun(null, 1, true);
        var failed = success(claim());
        executor = newExecutor(new com.npick.pipeline.domain.model.StageRetrySettings(
                Map.of("scene_detection", 3), java.util.Set.of("SCENE_DETECTION_FAILED")));
        failBody(failed, "failed", "SCENE_DETECTION_FAILED");
        complete(run, "scene_detection", failed);
        assertThat(row(run)).containsEntry("status", "failed");
        assertThat(state(run, "scene_detection")).containsEntry("attempts", 1).containsEntry("retryScheduled", false);
    }

    @Test
    void invalidStoredSubtitleRemainsPermanentThroughStorageWrapper() throws Exception {
        configure(new com.npick.pipeline.domain.model.StageRetrySettings(
                Map.of("transcript_selection", 3), java.util.Set.of("STAGE_FAILED", "INVALID_TRANSCRIPT")));
        long run = createRun(null, 1, true);
        long clip = ((Number) row(run).get("clip_id")).longValue();
        String subtitle = "clips/" + clip + "/invalid.srt";
        Files.writeString(media.resolve(subtitle), "invalid subtitle without timestamps");
        advanceToTranscript(run);
        var source = new com.npick.clip.application.command.StoredTranscriptPreparationService(
                (clipId, runId) -> new com.npick.clip.application.port.TranscriptPreparationSourcePort.Source(
                        "clips/" + clip + "/source.mp4", subtitle, java.math.BigDecimal.ONE),
                localTranscriptPreparation());
        var facade = preparedClaims(source, executor);
        rejects(() -> facade.claim(worker()), "CLIP_503_010");
        assertThat(state(run, "transcript_selection"))
                .containsEntry("status", "failed")
                .containsEntry("attempts", 1)
                .containsEntry("errorCode", "INVALID_TRANSCRIPT")
                .containsEntry("errorRetryable", false);
        assertThat(object(
                        object(state(run, "transcript_selection").get("error")).get("detail")))
                .containsEntry("sourceErrorCode", "CLIP_400_012");
        assertThat(object(claim().get("job"))).containsEntry("stage", "asr");
    }

    @Test
    void legacyPendingRetryRetainsItsReceiptBeforeWorkerChangesAndAddsNoNewBudget() throws Exception {
        configure(new com.npick.pipeline.domain.model.StageRetrySettings(
                Map.of("scene_detection", 2), java.util.Set.of("SCENE_DETECTION_FAILED")));
        long run = createRun(null, 1, true);
        var failed = success(claim());
        failBody(failed, "failed", "SCENE_DETECTION_FAILED");
        var receipt = new LinkedHashMap<>(complete(run, "scene_detection", failed));
        receipt.put("duplicate", true);
        jdbc.update("""
                UPDATE npick.pipeline_run SET stage_states_json = stage_states_json
                    #- '{stages,scene_detection,retryPolicy}'
                    #- '{stages,scene_detection,completions}'
                    #- '{stages,scene_detection,completedWorkerId}'
                WHERE pipeline_run_id=?
                """, run);
        executor = newExecutor(new com.npick.pipeline.domain.model.StageRetrySettings(
                Map.of("scene_detection", 5), java.util.Set.of("SCENE_DETECTION_FAILED")));
        var second = executor.reserve(new ClaimStageCommand("replacement", VERSIONS, Map.of()))
                .orElseThrow();
        assertThat(object(second.get("job"))).containsEntry("attempt", 2).containsEntry("maxAttempts", 2);
        assertThat(complete(run, "scene_detection", failed)).isEqualTo(receipt);
        var finalFailure = success(second);
        failBody(finalFailure, "failed", "SCENE_DETECTION_FAILED");
        executor.complete(new CompleteStageCommand(
                run, "scene_detection", "replacement", (String) finalFailure.get("idempotencyKey"), finalFailure));
        assertThat(row(run)).containsEntry("status", "failed");
        assertThat(complete(run, "scene_detection", failed)).isEqualTo(receipt);
        assertThat(object(state(run, "scene_detection").get("completions"))).hasSize(2);
    }

    @Test
    void transientPreparationFailureRetriesButUnknownAndPermanentFailuresDoNot() throws Exception {
        var policy = new com.npick.pipeline.domain.model.StageRetrySettings(
                Map.of("transcript_selection", 2), java.util.Set.of("STAGE_FAILED", "STAGE_TIMEOUT"));
        configure(policy);
        long run = createRun(null, 1, true);
        advanceToTranscript(run);
        var prepared = storedPreparation(run, localTranscriptPreparation());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var facade = preparedClaims(
                (clip, id, prefix) -> {
                    if (calls.incrementAndGet() == 1)
                        throw new BusinessException(
                                com.npick.clip.application.error.TranscriptErrorCode.STORAGE_FAILED,
                                new java.io.IOException("temporary I/O"));
                    return prepared.prepare(clip, id, prefix);
                },
                executor);
        rejects(() -> facade.claim(worker()), "CLIP_503_010");
        assertThat(state(run, "transcript_selection")).containsEntry("status", "pending");
        var second = facade.claim(worker()).orElseThrow();
        assertThat(object(second.get("job"))).containsEntry("attempt", 2).containsEntry("maxAttempts", 2);
        complete(run, "transcript_selection", success(second));
        assertThat(calls.get()).isEqualTo(2);
        for (RuntimeException failure : List.of(
                com.npick.clip.application.error.TranscriptErrorCode.invalid("subtitle", "bad input"),
                new BusinessException(
                        com.npick.clip.application.error.TranscriptErrorCode.STORAGE_FAILED,
                        new java.nio.file.NoSuchFileException("missing")),
                new IllegalStateException("unknown failure"))) {
            configure(policy);
            long rejected = createRun(null, 1, true);
            advanceToTranscript(rejected);
            var failing = preparedClaims(
                    (clip, id, prefix) -> {
                        throw failure;
                    },
                    executor);
            assertThatThrownBy(() -> failing.claim(worker())).isSameAs(failure);
            assertThat(state(rejected, "transcript_selection"))
                    .containsEntry("status", "failed")
                    .containsEntry("attempts", 1)
                    .containsEntry("errorRetryable", false);
            assertThat(object(claim().get("job"))).containsEntry("stage", "asr");
        }
    }

    @Test
    void allTenMockStagesCompleteThroughLeaseAndAtomicPersistence() throws Exception {
        long run = createRun(null, 1, true);
        List<String> visited = new ArrayList<>();
        Map<String, Object> first = null;
        for (String stage : PipelineStages.NAMES) {
            var assigned = claim();
            var job = object(assigned.get("job"));
            visited.add((String) job.get("stage"));
            assertThat(job).containsEntry("stage", stage).containsEntry("pipelineRunId", "" + run);
            assertThat(job).containsEntry("attempt", 1).containsEntry("maxAttempts", 1);
            if (stage.equals("asr"))
                assertThat(object(object(job.get("inputs")).get("upstream"))).containsKey("transcript");
            var body = success(assigned);
            var response = complete(run, stage, body);
            if (first == null) first = response;
            assertThat(response.get("duplicate")).isEqualTo(false);
        }
        assertThat(visited).containsExactlyElementsOf(PipelineStages.NAMES);
        assertThat(row(run)).containsEntry("status", "succeeded");
        assertThat(active(run)).isEqualTo(run);
        for (String stage : PipelineStages.NAMES) {
            assertThat(state(run, stage))
                    .containsEntry("status", "succeeded")
                    .containsEntry("attempts", 1)
                    .containsKeys("versions", "startedAt", "finishedAt", "durationMs", "lastRequestSha256", "output");
        }
        assertThat(state(run, "asr").get("output"))
                .isEqualTo(Map.of("segments", List.of(), "reasonCode", "NO_SPEECH_DETECTED"));
        assertThat(executor.reserve(worker())).isEmpty();
    }

    @Test
    void coveredSubtitleSkipsAsrWithoutInventingSpeechOrError() throws Exception {
        long run = createRun(null, 1, true);
        for (String stage : PipelineStages.NAMES) {
            if (stage.equals("asr")) continue;
            var assigned = claim();
            assertThat(object(assigned.get("job")).get("stage")).isEqualTo(stage);
            var body = success(assigned);
            if (stage.equals("transcript_selection"))
                body.put(
                        "output",
                        Map.of(
                                "transcript",
                                Map.of(
                                        "asrRequired",
                                        false,
                                        "candidateRanges",
                                        List.of(),
                                        "reasonCode",
                                        "SUBTITLE_COVERED")));
            complete(run, stage, body);
        }
        assertThat(state(run, "asr"))
                .containsEntry("status", "skipped")
                .containsEntry("attempts", 0)
                .containsEntry("reasonCode", "SUBTITLE_COVERED")
                .doesNotContainKey("errorCode");
        assertThat(row(run).get("status")).isEqualTo("succeeded");
    }

    @Test
    void nonfatalFailureAndNoAdapterContinueButFatalFailureStopsOnlyItsRun() throws Exception {
        long run = createRun(null, 1, true);
        for (String stage : PipelineStages.NAMES) {
            var assigned = claim();
            var body = success(assigned);
            if (stage.equals("asr")) failBody(body, "failed", "ASR_FAILED");
            if (stage.equals("ocr")) failBody(body, "skipped", "NO_ADAPTER");
            complete(run, stage, body);
        }
        assertThat(row(run).get("status")).isEqualTo("succeeded");
        assertThat(state(run, "asr")).containsEntry("status", "failed").containsEntry("errorCode", "ASR_FAILED");
        assertThat(state(run, "ocr")).containsEntry("status", "skipped").containsEntry("errorCode", "NO_ADAPTER");
        long failed = createRun(null, 1, true);
        var assigned = claim();
        var body = success(assigned);
        failBody(body, "failed", "SCENE_DETECTION_FAILED");
        complete(failed, "scene_detection", body);
        assertThat(row(failed)).containsEntry("status", "failed").containsEntry("error_code", "SCENE_DETECTION_FAILED");
        assertThat(active(failed)).isNull();
        long independent = createRun(null, 1, true);
        assertThat(object(claim().get("job")).get("pipelineRunId")).isEqualTo("" + independent);
    }

    @Test
    void duplicateReturnsOriginalResponseAndRejectsChangedPayloadEvenAfterNextStage() throws Exception {
        long run = createRun(null, 1, true);
        var body = success(claim());
        var first = complete(run, "scene_detection", body);
        var next = claim();
        assertThat(object(next.get("job")).get("stage")).isEqualTo("frame_extraction");
        var duplicate = complete(run, "scene_detection", body);
        var expected = new LinkedHashMap<>(first);
        expected.put("duplicate", true);
        assertThat(duplicate).isEqualTo(expected);
        assertThat(sceneCount(run)).isEqualTo(1);
        body.put("metrics", Map.of("changed", true));
        rejects(() -> complete(run, "scene_detection", body), "JOB_409_003");
        assertThat(sceneCount(run)).isEqualTo(1);
    }

    @Test
    void expiredLeaseCannotHeartbeatOrCompleteAndReclaimDoesNotConsumeAttempt() throws Exception {
        long run = createRun(null, 1, true);
        var assigned = claim();
        var oldBody = success(assigned);
        UUID oldLease = UUID.fromString((String) oldBody.get("leaseId"));
        clock.advance(61);
        rejects(() -> executor.heartbeat(run, "scene_detection", "mock", oldLease), "JOB_409_002");
        rejects(() -> complete(run, "scene_detection", oldBody), "JOB_409_002");
        assertThat(executor.reclaim()).isZero();
        clock.advance(15);
        assertThat(executor.reclaim()).isEqualTo(1);
        var replacement = claim();
        assertThat(object(replacement.get("job")).get("attempt")).isEqualTo(1);
        assertThat(object(replacement.get("lease")).get("leaseId")).isNotEqualTo(oldLease.toString());
        rejects(() -> complete(run, "scene_detection", oldBody), "JOB_409_002");
        var fresh = success(replacement);
        complete(run, "scene_detection", fresh);
        rejects(() -> complete(run, "scene_detection", oldBody), "JOB_409_002");
        assertThat(sceneCount(run)).isEqualTo(1);
    }

    @Test
    void crossRunWrongStageAttemptAndMalformedResultCannotWrite() throws Exception {
        long first = createRun(null, 1, true);
        long second = createRun(null, 1, true);
        var body = success(claim());
        rejects(() -> complete(second, "scene_detection", body), "JOB_409_002");
        var wrong = new LinkedHashMap<>(body);
        wrong.put("stage", "ocr");
        var wrongVersions = new LinkedHashMap<>(object(body.get("versions")));
        wrongVersions.put("outputSchemaVersion", PipelineStages.outputSchema("ocr"));
        wrong.put("versions", wrongVersions);
        rejects(() -> complete(first, "ocr", wrong), "JOB_409_002");
        var attempt = new LinkedHashMap<>(body);
        attempt.put("attempt", 2);
        rejects(() -> complete(first, "scene_detection", attempt), "JOB_400_001");
        var invalid = new LinkedHashMap<>(body);
        invalid.put("output", Map.of());
        rejects(() -> complete(first, "scene_detection", invalid), "JOB_400_001");
        assertThat(sceneCount(first)).isZero();
        assertThat(sceneCount(second)).isZero();
        assertThat(state(first, "scene_detection").get("status")).isEqualTo("running");
    }

    @Test
    void persistenceFailureRollsBackSceneAndStateAndSameLeaseCanResubmit() throws Exception {
        long run = createRun(null, 1, true);
        var body = success(claim());
        outputs.throwAfterInsert = true;
        assertThatThrownBy(() -> complete(run, "scene_detection", body)).isInstanceOf(IllegalStateException.class);
        assertThat(sceneCount(run)).isZero();
        assertThat(state(run, "scene_detection"))
                .containsEntry("status", "running")
                .doesNotContainKey("lastIdempotencyKey");
        outputs.throwAfterInsert = false;
        complete(run, "scene_detection", body);
        assertThat(sceneCount(run)).isEqualTo(1);
    }

    @Test
    void versionMismatchRecordsResultButNeverActivates() throws Exception {
        long run = createRun(null, 1, true);
        for (String stage : PipelineStages.NAMES) {
            var body = success(claim());
            if (stage.equals("vlm_metadata")) {
                var versions = new LinkedHashMap<>(object(body.get("versions")));
                versions.put("stageVersion", "npick.stage.vlm_metadata/v1:ffffffff");
                body.put("versions", versions);
            }
            complete(run, stage, body);
        }
        assertThat(state(run, "vlm_metadata"))
                .containsEntry("versionMismatch", true)
                .containsEntry("status", "succeeded");
        assertThat(row(run)).containsEntry("status", "failed").containsEntry("error_code", "PIPELINE_VERSION_MISMATCH");
        assertThat(active(run)).isNull();
    }

    @Test
    void missingSearchTextOrMediaDoesNotActivateAndFailedReprocessingPreservesActiveRun() throws Exception {
        long original = createRun(null, 1, true);
        finish(original);
        long clip = ((Number) row(original).get("clip_id")).longValue();
        long reprocess = createRun(clip, 2, true);
        outputs.searchText = false;
        finish(reprocess);
        assertThat(row(reprocess)).containsEntry("status", "failed").containsEntry("error_code", "INDEX_FAILED");
        assertThat(active(reprocess)).isEqualTo(original);
        outputs.searchText = true;
        long absent = createRun(null, 1, false);
        finish(absent);
        assertThat(row(absent).get("status")).isEqualTo("failed");
        assertThat(active(absent)).isNull();
    }

    @Test
    void delayedOlderProcessingCannotReplaceNewerActiveRun() throws Exception {
        long old = createRun(null, 1, true);
        long clip = ((Number) row(old).get("clip_id")).longValue();
        // 첫 실행의 lease를 유지하는 동안 두 번째 실행을 끝낸다.
        var delayed = success(claim());
        long newer = createRun(clip, 2, true);
        finish(newer);
        complete(old, "scene_detection", delayed);
        for (int i = 1; i < PipelineStages.NAMES.size(); i++)
            complete(old, PipelineStages.NAMES.get(i), success(claim()));
        assertThat(active(old)).isEqualTo(newer);
        assertThat(row(old).get("status")).isEqualTo("failed");
    }

    @Test
    void concurrentClaimsAssignSingleLeaseAndConcurrentCompletionWritesOnce() throws Exception {
        long run = createRun(null, 1, true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> executor.reserve(worker()));
            var b = pool.submit(() -> executor.reserve(worker()));
            var left = a.get(10, TimeUnit.SECONDS);
            var right = b.get(10, TimeUnit.SECONDS);
            assertThat((left.isPresent() ? 1 : 0) + (right.isPresent() ? 1 : 0)).isEqualTo(1);
            var body = success(left.orElseGet(right::orElseThrow));
            var first = pool.submit(() -> complete(run, "scene_detection", body));
            var second = pool.submit(() -> complete(run, "scene_detection", body));
            assertThat(List.of(
                            first.get(10, TimeUnit.SECONDS).get("duplicate"),
                            second.get(10, TimeUnit.SECONDS).get("duplicate")))
                    .containsExactlyInAnyOrder(false, true);
        }
        assertThat(sceneCount(run)).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void independentRunsRemainClaimableBeforeFirstClaimCommits(boolean differentCapabilities) throws Exception {
        long older = createRun(null, 1, true);
        if (differentCapabilities) complete(older, "scene_detection", success(claim()));
        long newer = createRun(null, 1, true);
        var reserved = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var sceneWorker = new ClaimStageCommand(
                "scene-worker", Map.of("scene_detection", VERSIONS.get("scene_detection")), Map.of());
        String otherStage = differentCapabilities ? "frame_extraction" : "scene_detection";
        var otherWorker = new ClaimStageCommand("other-worker", Map.of(otherStage, VERSIONS.get(otherStage)), Map.of());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(status -> {
                        var assignment = executor.reserve(sceneWorker).orElseThrow();
                        reserved.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("second claim did not finish");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        return assignment;
                    }));
            try {
                assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.reserve(otherWorker).orElseThrow();
                assertThat(object(second.get("job")).get("pipelineRunId"))
                        .isEqualTo("" + (differentCapabilities ? older : newer));
                assertThat(object(second.get("job")).get("stage")).isEqualTo(otherStage);
            } finally {
                release.countDown();
            }
            assertThat(object(first.get(10, TimeUnit.SECONDS).get("job")).get("pipelineRunId"))
                    .isEqualTo("" + (differentCapabilities ? newer : older));
        }
    }

    @Test
    void candidatesBeyondFirstBatchAreNotStarvedByIncompatibleVersions() throws Exception {
        for (int i = 0; i < 65; i++) {
            long incompatible = createRun(null, 1, true);
            jdbc.update(
                    "UPDATE npick.pipeline_run SET pipeline_version='incompatible-pipeline' WHERE pipeline_run_id=?",
                    incompatible);
        }
        long compatible = createRun(null, 1, true);
        assertThat(object(claim().get("job")).get("pipelineRunId")).isEqualTo("" + compatible);
    }

    @Test
    void legacyFlatStatesAreWrappedWithoutLosingMetadataAndUnknownVersionsAreNotClaimed() throws Exception {
        long run = createRun(null, 1, true);
        jdbc.update(
                "UPDATE npick.pipeline_run SET stage_states_json=stage_states_json->'stages' WHERE pipeline_run_id=?",
                run);
        var assigned = claim();
        assertThat(row(run).get("stage_states_json").toString()).contains("npick.stage_states/v1");
        complete(run, "scene_detection", success(assigned));
        assertThat(executor.reserve(new ClaimStageCommand("mock", Map.of("frame_extraction", "different"), Map.of())))
                .isEmpty();
    }

    @Test
    void artifactsCannotReferenceOtherRunsOrUnregisteredTranscriptSnapshots() throws Exception {
        long run = createRun(null, 1, true);
        var assigned = claim();
        var body = success(assigned);
        var artifact = Map.<String, Object>of(
                "kind",
                "transcript_segments",
                "storageKey",
                "runs/other/scene_detection/a1/segments.json",
                "byteSize",
                1,
                "contentHash",
                "a".repeat(64));
        body.put("artifacts", List.of(artifact));
        rejects(() -> complete(run, "scene_detection", body), "JOB_403_001");
        var ownArtifact = new LinkedHashMap<>(artifact);
        ownArtifact.put("storageKey", object(assigned.get("job")).get("outputKeyPrefix") + "segments.json");
        body.put("artifacts", List.of(ownArtifact));
        body.put("output", Map.of("transcript", Map.of("segmentsArtifact", artifact)));
        rejects(() -> complete(run, "scene_detection", body), "JOB_400_001");
        assertThat(sceneCount(run)).isZero();
        body.put("output", Map.of("transcript", Map.of("segmentsArtifact", ownArtifact)));
        complete(run, "scene_detection", body);
        assertThat(state(run, "scene_detection").get("artifacts")).isEqualTo(List.of(ownArtifact));
        assertThat(object(state(run, "scene_detection").get("output")))
                .containsEntry("transcript", Map.of("segmentsArtifact", ownArtifact));
    }

    @Test
    void unrecognizedLegacyRunDoesNotBlockAnIndependentQueuedRun() throws Exception {
        long legacy = createRun(null, 1, true);
        jdbc.update("UPDATE npick.pipeline_run SET stage_states_json='{}'::jsonb WHERE pipeline_run_id=?", legacy);
        long valid = createRun(null, 1, true);
        assertThat(object(claim().get("job")).get("pipelineRunId")).isEqualTo("" + valid);
        assertThat(row(legacy).get("stage_states_json").toString()).isEqualTo("{}");
    }

    @Test
    void mergedTranscriptPreparationIsAttachedOutsideTransactionWithExactDurationAndOwnedArtifacts() throws Exception {
        long run = createRun(null, 1, true);
        advanceToTranscript(run);
        var reference = new java.util.concurrent.atomic.AtomicReference<
                com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.Command>();
        var actual = localTranscriptPreparation();
        var stored =
                storedPreparation(run, command -> {
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                                    .isActualTransactionActive())
                            .isFalse();
                    reference.set(command);
                    return actual.prepare(command);
                });
        var facade = preparedClaims(stored, executor);
        var assignment = facade.claim(worker()).orElseThrow();
        assertThat(reference.get().videoDuration()).isEqualTo(new java.math.BigDecimal("1.000001"));
        var transcript = object(
                        object(object(assignment.get("job")).get("inputs")).get("upstream"))
                .get("transcript");
        assertThat(object(transcript)).containsKeys("segmentsArtifact", "embeddedInspection");
        String key = (String) object(object(transcript).get("segmentsArtifact")).get("storageKey");
        assertThat(media.resolve(key)).exists();
        assertThat(JSON.readTree(Files.readAllBytes(media.resolve(key)))
                        .path("schemaVersion")
                        .asText())
                .isEqualTo("npick.transcript.segments/v1");
        assertThat(HASH.hash(object(state(run, "transcript_selection").get("preparedTranscript"))))
                .isEqualTo(HASH.hash(object(transcript)));
        assertThat(state(run, "transcript_selection")).containsKey("inputArtifacts");
        var result = success(assignment);
        result.put(
                "output",
                Map.of(
                        "transcript",
                        Map.of("asrRequired", false, "candidateRanges", List.of(), "reasonCode", "SUBTITLE_COVERED")));
        complete(run, "transcript_selection", result);
        assertThat(state(run, "asr")).containsEntry("status", "skipped").containsEntry("attempts", 0);
        assertThat(media.resolve(key)).exists();
    }

    @Test
    void reclaimedPreparationCannotAttachAndItsUnretainedFileIsCleaned() throws Exception {
        long run = createRun(null, 1, true);
        advanceToTranscript(run);
        var actual = storedPreparation(run, localTranscriptPreparation());
        var owned = new java.util.concurrent.atomic.AtomicReference<Path>();
        var facade = preparedClaims(
                (clip, id, prefix) -> {
                    var prepared = actual.prepare(clip, id, prefix);
                    owned.set(media.resolve(
                            prepared.transcript().segmentsArtifact().storageKey()));
                    clock.advance(76);
                    assertThat(executor.reclaim()).isEqualTo(1);
                    executor.reserve(worker()).orElseThrow();
                    return prepared;
                },
                executor);
        rejects(() -> facade.claim(worker()), "JOB_409_002");
        assertThat(owned.get()).doesNotExist();
        assertThat(state(run, "transcript_selection")).doesNotContainKey("preparedTranscript");
    }

    @Test
    void uncertainAttachCommitRetainsPreparedFileAndPreparationFailureUsesCommonCompletion() throws Exception {
        long run = createRun(null, 1, true);
        advanceToTranscript(run);
        var facade = preparedClaims(
                storedPreparation(run, localTranscriptPreparation()), (id, worker, lease, input, artifacts) -> {
                    executor.attach(id, worker, lease, input, artifacts);
                    throw new IllegalStateException("simulated lost commit acknowledgement");
                });
        assertThatThrownBy(() -> facade.claim(worker())).isInstanceOf(IllegalStateException.class);
        var recorded = object(state(run, "transcript_selection").get("preparedTranscript"));
        assertThat(media.resolve(
                        (String) object(recorded.get("segmentsArtifact")).get("storageKey")))
                .exists();

        clock.advance(76);
        executor.reclaim();
        var failing = preparedClaims(
                (clip, id, prefix) -> {
                    throw new BusinessException(com.npick.clip.application.error.TranscriptErrorCode.STORAGE_FAILED);
                },
                executor);
        rejects(() -> failing.claim(worker()), "CLIP_503_010");
        assertThat(state(run, "transcript_selection"))
                .containsEntry("status", "failed")
                .containsEntry("errorCode", "STAGE_FAILED");
        assertThat(object(state(run, "transcript_selection").get("versions"))).containsEntry("stageVersion", "unknown");
        assertThat(object(executor.reserve(worker()).orElseThrow().get("job")).get("stage"))
                .isEqualTo("asr");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "NPICK_MEDIA_TESTS", matches = "true")
    void preparesRegisteredVideoUsingActualFfprobeAndMergedInputUseCase() throws Exception {
        long run = createRun(null, 1, true);
        long clip = ((Number) row(run).get("clip_id")).longValue();
        Path video = media.resolve("clips/" + clip + "/source.mp4");
        Process generator = new ProcessBuilder(
                        "ffmpeg",
                        "-v",
                        "error",
                        "-y",
                        "-nostdin",
                        "-f",
                        "lavfi",
                        "-i",
                        "color=s=160x120:r=25",
                        "-t",
                        "1.2",
                        "-c:v",
                        "libx264",
                        video.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            assertThat(generator.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(generator.exitValue()).isZero();
        } finally {
            if (generator.isAlive()) generator.destroyForcibly().waitFor();
        }
        String subtitle = "clips/" + clip + "/provided.srt";
        Files.writeString(media.resolve(subtitle), "1\n00:00:00,001 --> 00:00:01,100\n한글 자막\n");
        jdbc.update("UPDATE npick.clip SET transcript_file_key=? WHERE clip_id=?", subtitle, clip);
        var properties = new ClipRegistrationProperties(
                media,
                media.resolve("upload"),
                java.time.Duration.ofSeconds(20),
                java.time.Duration.ofSeconds(20),
                null,
                false,
                10485760);
        var source = new com.npick.clip.infrastructure.transcript.StoredTranscriptSourceAdapter(jdbc, properties, JSON);
        var expected = new com.npick.clip.infrastructure.media.FfprobeVideoReader(
                        "ffprobe", properties.probeTimeout(), JSON)
                .readVideo(video)
                .durationSeconds();
        assertThat(source.load(clip, run).videoDuration()).isEqualTo(expected);
        assertThatThrownBy(() -> source.load(clip, run + 99999)).isInstanceOf(BusinessException.class);
        var parser = new com.npick.clip.infrastructure.transcript.SubtitleParser();
        var actual = new com.npick.clip.infrastructure.transcript.LocalTranscriptInputPreparation(
                media,
                10485760,
                parser,
                new com.npick.clip.infrastructure.transcript.MovTextExtractor(
                        new com.npick.clip.infrastructure.transcript.SubtitleProcess(),
                        parser,
                        JSON,
                        properties.probeTimeout(),
                        10485760),
                JSON);
        advanceToTranscript(run);
        var facade = preparedClaims(
                new com.npick.clip.application.command.StoredTranscriptPreparationService(source, actual), executor);
        var assignment = facade.claim(worker()).orElseThrow();
        var input = object(
                object(object(object(assignment.get("job")).get("inputs")).get("upstream"))
                        .get("transcript"));
        String key = (String) object(input.get("segmentsArtifact")).get("storageKey");
        var segments = JSON.readTree(Files.readAllBytes(media.resolve(key))).path("segments");
        assertThat(segments.size()).isEqualTo(1);
        assertThat(segments.get(0).path("t").asText()).isEqualTo("한글 자막");
        assertThat(segments.get(0).path("sourceDetail").asText()).isEqualTo("uploaded");
    }

    private void advanceToTranscript(long run) {
        for (String stage : PipelineStages.NAMES.subList(0, 4)) complete(run, stage, success(claim()));
    }

    private com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase localTranscriptPreparation() {
        var extractor = org.mockito.Mockito.mock(com.npick.clip.infrastructure.transcript.MovTextExtractor.class);
        org.mockito.Mockito.when(
                        extractor.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.npick.clip.infrastructure.transcript.MovTextExtractor.Extraction(
                        List.of(),
                        new com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.EmbeddedInspection(
                                com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.EmbeddedStatus
                                        .NO_TRACK,
                                null,
                                List.of(),
                                false)));
        return new com.npick.clip.infrastructure.transcript.LocalTranscriptInputPreparation(
                media, 10485760, new com.npick.clip.infrastructure.transcript.SubtitleParser(), extractor, JSON);
    }

    private com.npick.clip.application.command.prepare.PrepareStoredTranscriptInputUseCase storedPreparation(
            long run, com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase preparation) {
        return new com.npick.clip.application.command.StoredTranscriptPreparationService(
                (clip, id) -> {
                    assertThat(id).isEqualTo(run);
                    return new com.npick.clip.application.port.TranscriptPreparationSourcePort.Source(
                            "clips/" + clip + "/source.mp4", null, new java.math.BigDecimal("1.000001"));
                },
                preparation);
    }

    private com.npick.pipeline.application.command.PreparedStageClaimService preparedClaims(
            com.npick.clip.application.command.prepare.PrepareStoredTranscriptInputUseCase preparation,
            com.npick.pipeline.application.command.claim.AttachStageInputUseCase attach) {
        return new com.npick.pipeline.application.command.PreparedStageClaimService(
                executor,
                attach,
                executor,
                preparation,
                (run, stage, worker, lease) -> new com.npick.pipeline.application.port.PreparationLeasePort.Guard() {
                    public void verify() {}

                    public void close() {}
                });
    }

    private void finish(long run) {
        for (String stage : PipelineStages.NAMES) complete(run, stage, success(claim()));
    }

    private Map<String, Object> claim() {
        return executor.reserve(worker()).orElseThrow();
    }

    private ClaimStageCommand worker() {
        return new ClaimStageCommand("mock", VERSIONS, Map.of("kind", "cpu", "gpuModel", "none"));
    }

    private Map<String, Object> complete(long run, String stage, Map<String, Object> body) {
        return executor.complete(
                new CompleteStageCommand(run, stage, "mock", (String) body.get("idempotencyKey"), body));
    }

    private Map<String, Object> success(Map<String, Object> assignment) {
        var job = object(assignment.get("job"));
        String stage = (String) job.get("stage");
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("stageVersion", VERSIONS.get(stage));
        version.put("outputSchemaVersion", PipelineStages.outputSchema(stage));
        version.put("configVersion", null);
        version.put("modelVersion", null);
        version.put("promptVersion", null);
        version.put("detail", Map.of("implementation", "mock"));
        version.put("runtime", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("envelopeVersion", "stage-result/v1");
        body.put("leaseId", object(assignment.get("lease")).get("leaseId"));
        body.put("idempotencyKey", job.get("idempotencyKey"));
        body.put("stage", stage);
        body.put("attempt", job.get("attempt"));
        body.put("status", "succeeded");
        body.put("startedAt", clock.instant().toString());
        body.put("finishedAt", clock.instant().toString());
        body.put("durationMs", 0);
        body.put("versions", version);
        body.put(
                "output",
                stage.equals("asr")
                        ? Map.of("segments", List.of(), "reasonCode", "NO_SPEECH_DETECTED")
                        : stage.equals("transcript_selection")
                                ? Map.of(
                                        "transcript",
                                        Map.of(
                                                "asrRequired",
                                                true,
                                                "candidateRanges",
                                                List.of(Map.of("s", 0, "e", 1000)),
                                                "reasonCode",
                                                "NO_VALID_SUBTITLE"))
                                : Map.of("mock", true));
        body.put("artifacts", List.of());
        body.put("warnings", List.of());
        body.put("error", null);
        return body;
    }

    private static void failBody(Map<String, Object> body, String status, String code) {
        body.put("status", status);
        body.put("output", null);
        body.put("error", Map.of("code", code, "retryable", !code.equals("NO_ADAPTER"), "message", "mock failure"));
    }

    private long createRun(Long existingClip, int processingNo, boolean exists) throws Exception {
        long clip = existingClip == null ? ++sequence : existingClip;
        long run = ++sequence;
        if (existingClip == null) {
            String key = "clips/" + clip + "/source.mp4";
            if (exists) {
                Files.createDirectories(media.resolve(key).getParent());
                Files.writeString(media.resolve(key), "validated mock media");
            }
            jdbc.update("""
                INSERT INTO npick.clip (clip_id,source_type,storage_key,content_hash,transcript_source,registered_by_id,created_at,updated_at)
                VALUES (?,'archive',?,?,'none',1,now(),now())
                """, clip, key, HASH.hash(Map.of("clip", clip)));
        }
        Map<String, Object> stages = new LinkedHashMap<>();
        PipelineStages.NAMES.forEach(s -> stages.put(s, Map.of("status", "pending", "attempts", 0)));
        jdbc.update(
                """
            INSERT INTO npick.pipeline_run (pipeline_run_id,clip_id,processing_no,pipeline_version,status,stage_states_json,created_at,updated_at)
            VALUES (?,?,?,?,'queued',?::jsonb,now(),now())
            """,
                run,
                clip,
                processingNo,
                VERSION,
                JSON.writeValueAsString(Map.of("schemaVersion", "npick.stage_states/v1", "stages", stages)));
        return run;
    }

    private Map<String, Object> row(long run) {
        return jdbc.queryForMap("SELECT * FROM npick.pipeline_run WHERE pipeline_run_id=?", run);
    }

    private Map<String, Object> state(long run, String stage) {
        Map<String, Object> value =
                JSON.readValue(row(run).get("stage_states_json").toString(), new TypeReference<>() {});
        return object(object(value.get("stages")).get(stage));
    }

    private Long active(long run) {
        return jdbc.queryForObject(
                "SELECT c.active_pipeline_run_id FROM npick.clip c JOIN npick.pipeline_run r USING(clip_id) WHERE r.pipeline_run_id=?",
                Long.class,
                run);
    }

    private int sceneCount(long run) {
        return jdbc.queryForObject("SELECT count(*) FROM npick.scene WHERE pipeline_run_id=?", Integer.class, run);
    }

    private static Map<String, Object> object(Object value) {
        return JsonValues.object(value);
    }

    private static Map<String, String> versions() {
        Map<String, String> result = new LinkedHashMap<>();
        PipelineStages.NAMES.forEach(s -> result.put(
                s,
                "npick.stage." + s + "/v1:"
                        + HASH.hash(Map.of("implementation", "mock", "stage", s))
                                .substring(0, 8)));
        return result;
    }

    private static void rejects(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo(code));
    }

    private static StageExecutionService transactional(StageExecutionService service) {
        var proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return (StageExecutionService) proxy.getProxy();
    }

    private class MockOutputs implements StageOutputPort {
        boolean throwAfterInsert;
        boolean searchText = true;

        public Map<String, Object> validateAndStore(
                long run, long clip, String stage, String prefix, Map<String, Object> result) {
            if (stage.equals("scene_detection")) {
                long scene = run * 100;
                jdbc.update("""
                    INSERT INTO npick.scene(scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,shot_type,created_at,updated_at)
                    VALUES (?,?,?,0,1000,'unknown',now(),now())
                    """, scene, clip, run);
                if (throwAfterInsert) throw new IllegalStateException("mock persistence failure");
                return Map.of("scenes", List.of(Map.of("sceneIndex", 0, "sceneId", "" + scene)));
            }
            if (stage.equals("frame_extraction")) {
                String key = prefix + "frame.png";
                try {
                    Files.createDirectories(media.resolve(key).getParent());
                    Files.writeString(media.resolve(key), "mock frame");
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
                jdbc.update("INSERT INTO npick.keyframe VALUES (?,?,0,?)", run * 100 + 1, run * 100, key);
            }
            if (stage.equals("vlm_metadata") && searchText)
                jdbc.update(
                        "UPDATE npick.scene SET caption='mock caption',caption_tokens='mock caption' WHERE pipeline_run_id=? AND clip_id=?",
                        run,
                        clip);
            return Map.of();
        }
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-09T00:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }
}
