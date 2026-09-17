package com.npick.pipeline.application.command;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;

import com.npick.clip.application.command.activate.ActivateProcessedClipUseCase;
import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.command.claim.AttachStageInputUseCase;
import com.npick.pipeline.application.command.claim.ClaimStageCommand;
import com.npick.pipeline.application.command.claim.ReserveStageUseCase;
import com.npick.pipeline.application.command.complete.CompleteStageCommand;
import com.npick.pipeline.application.command.complete.CompleteStageUseCase;
import com.npick.pipeline.application.command.heartbeat.HeartbeatStageUseCase;
import com.npick.pipeline.application.command.reclaim.ReclaimStagesUseCase;
import com.npick.pipeline.application.error.JobErrorCode;
import com.npick.pipeline.application.port.JobJsonPort;
import com.npick.pipeline.application.port.StageOutputPort;
import com.npick.pipeline.application.query.definition.GetPipelineDefinitionUseCase;
import com.npick.pipeline.domain.error.PipelineErrorCode;
import com.npick.pipeline.domain.model.JsonValues;
import com.npick.pipeline.domain.model.PipelineRun;
import com.npick.pipeline.domain.model.PipelineStages;
import com.npick.pipeline.domain.model.StageRetrySettings;
import com.npick.pipeline.domain.repository.PipelineRunRepository;

/** HTTP·오프라인 반입·mock이 공유할 유일한 배정 및 수락 경계. 단계 함수는 DB 트랜잭션 밖에서 실행한다. */
public class StageExecutionService
        implements ReserveStageUseCase,
                AttachStageInputUseCase,
                CompleteStageUseCase,
                HeartbeatStageUseCase,
                ReclaimStagesUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(StageExecutionService.class);

    private final PipelineRunRepository runs;
    private final GetPipelineDefinitionUseCase definitions;
    private final StageOutputPort outputs;
    private final JobJsonPort json;
    private final Clock clock;
    private final ActivateProcessedClipUseCase publication;
    private final StageRetrySettings retries;

    public StageExecutionService(
            PipelineRunRepository runs,
            GetPipelineDefinitionUseCase definitions,
            StageOutputPort outputs,
            JobJsonPort json,
            Clock clock,
            ActivateProcessedClipUseCase publication) {
        this(runs, definitions, outputs, json, clock, publication, StageRetrySettings.disabled());
    }

    public StageExecutionService(
            PipelineRunRepository runs,
            GetPipelineDefinitionUseCase definitions,
            StageOutputPort outputs,
            JobJsonPort json,
            Clock clock,
            ActivateProcessedClipUseCase publication,
            StageRetrySettings retries) {
        this.runs = runs;
        this.definitions = definitions;
        this.outputs = outputs;
        this.json = json;
        this.clock = clock;
        this.publication = publication;
        this.retries = retries;
    }

    @Override
    @Transactional
    public Optional<Map<String, Object>> reserve(ClaimStageCommand command) {
        var definition = definitions.get();
        PipelineRunRepository.Cursor cursor = null;
        while (true) {
            var candidates = runs.candidates(cursor, command.capabilities());
            if (candidates.isEmpty()) return Optional.empty();
            for (var candidate : candidates) {
                cursor = candidate.cursor();
                if (candidate.run() == null || !matches(candidate.run(), command, definition)) continue;
                var locked = runs.tryLockCandidate(cursor.id());
                if (locked.isEmpty()) continue;
                PipelineRun run = locked.orElseThrow();
                if (!matches(run, command, definition)) continue;
                var snapshot = run.snapshot();
                // 구 버전을 현재 모델 조합으로 조용히 바꾸지 않는다.
                Map<String, String> expected = run.expectedVersions();
                if (expected.isEmpty()) {
                    if (!definition.version().equals(snapshot.pipelineVersion())) continue;
                    expected = definition.stageVersions();
                }
                String stage = run.nextStage();
                if (stage == null
                        || !expected.get(stage).equals(command.capabilities().get(stage))
                        || "unknown".equals(command.capabilities().get(stage))) continue;
                run.bindExpectedVersions(snapshot.pipelineVersion(), expected);
                Instant now = clock.instant();
                run.claim(stage, command.workerId(), UUID.randomUUID(), command.device(), now, retries);
                runs.save(run, now);
                putJobContext(snapshot.clipId(), snapshot.id(), stage);
                try {
                    LOG.info(
                            "스테이지를 배정했다 worker={} attempt={}",
                            command.workerId(),
                            run.state(stage).get("attempts"));
                    return Optional.of(assignment(run, stage));
                } finally {
                    clearJobContext();
                }
            }
        }
    }

    private boolean matches(
            PipelineRun run, ClaimStageCommand command, GetPipelineDefinitionUseCase.Definition definition) {
        var expected = run.expectedVersions();
        if (expected.isEmpty()) {
            if (!definition.version().equals(run.snapshot().pipelineVersion())) return false;
            expected = definition.stageVersions();
        }
        String stage = run.nextStage();
        return stage != null
                && !"unknown".equals(command.capabilities().get(stage))
                && java.util.Objects.equals(
                        expected.get(stage), command.capabilities().get(stage));
    }

    private Map<String, Object> assignment(PipelineRun run, String stage) {
        var snapshot = run.snapshot();
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("pipelineRunId", Long.toString(snapshot.id()));
        job.put("clipId", Long.toString(snapshot.clipId()));
        job.put("processingNo", snapshot.processingNo());
        job.put("stage", stage);
        job.put("attempt", run.state(stage).get("attempts"));
        job.put("maxAttempts", run.maxAttempts(stage));
        job.put("idempotencyKey", run.idempotencyKey(stage));
        job.put("pipelineVersion", snapshot.pipelineVersion());
        job.put("expectedStageVersion", run.state(stage).get("expectedStageVersion"));
        job.put("outputSchemaVersion", PipelineStages.outputSchema(stage));
        job.put("outputKeyPrefix", run.outputKeyPrefix(stage));
        job.put("deadlineAt", null);
        // #70이 media/config 전송을 연결한다. transcript는 승인된 upstream 이름으로 보존한다.
        var upstream = new LinkedHashMap<>(run.upstream());
        var prepared = JsonValues.object(run.state(stage).get("preparedTranscript"));
        if (!prepared.isEmpty()) upstream.put("transcript", prepared);
        job.put("inputs", Map.of("upstream", upstream));
        return JsonValues.copy(Map.of(
                "assigned",
                true,
                "revokedLeases",
                List.of(),
                "lease",
                Map.of(
                        "leaseId",
                        snapshot.leaseId().toString(),
                        "leaseUntil",
                        snapshot.leaseExpiresAt().toString(),
                        "heartbeatIntervalMs",
                        10000),
                "job",
                job));
    }

    @Override
    @Transactional
    public Map<String, Object> complete(CompleteStageCommand command) {
        Map<String, Object> body = command.result();
        validateEnvelope(command);
        UUID lease = UUID.fromString((String) body.get("leaseId"));
        PipelineRun run = requireRun(command.runId());
        putJobContext(run.clipId(), command.runId(), command.stage());
        try {
            return accept(command, run, body, lease);
        } finally {
            clearJobContext();
        }
    }

    private Map<String, Object> accept(
            CompleteStageCommand command, PipelineRun run, Map<String, Object> body, UUID lease) {
        String hash = json.hash(body);
        var duplicate = run.duplicate(command.stage(), lease, command.workerId(), command.idempotencyKey(), hash);
        if (duplicate != null) {
            Map<String, Object> response = new LinkedHashMap<>(duplicate);
            response.put("duplicate", true);
            LOG.info("이미 수락한 결과의 재전송이다 worker={}", command.workerId());
            return JsonValues.copy(response);
        }
        Instant now = clock.instant();
        run.fence(command.stage(), lease, command.workerId(), now);
        // 도메인 가드를 먼저 적용한다. 검증/정본 저장이 실패하면 행 갱신도 함께 롤백된다.
        String prefix = run.outputKeyPrefix(command.stage());
        validateArtifacts(body, prefix);
        run.complete(command.stage(), body, hash, now, retries);
        Map<String, Object> ids = Map.of();
        var snapshot = run.snapshot();
        if ("succeeded".equals(body.get("status"))) {
            ids = outputs.validateAndStore(snapshot.id(), snapshot.clipId(), command.stage(), prefix, body);
            if ("indexing".equals(command.stage())) {
                boolean ready = run.versionsMatch()
                        && publication.activate(snapshot.clipId(), snapshot.id(), snapshot.processingNo());
                run.finishIndexing(ready, now);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", true);
        response.put("duplicate", false);
        response.put("runStatus", run.snapshot().status());
        response.put(
                "stageState",
                Map.of(
                        "status",
                        run.state(command.stage()).get("status"),
                        "attempts",
                        run.state(command.stage()).get("attempts")));
        response.put("assignedIds", ids);
        response.put("next", null);
        run.recordAssignedIds(command.stage(), ids);
        run.rememberCompletion(command.stage(), response);
        runs.save(run, now);
        LOG.info(
                "스테이지 결과를 수락했다 worker={} status={} runStatus={}",
                command.workerId(),
                body.get("status"),
                response.get("runStatus"));
        return JsonValues.copy(response);
    }

    @Override
    @Transactional
    public Instant heartbeat(long runId, String stage, String workerId, UUID leaseId) {
        PipelineRun run = requireRun(runId);
        putJobContext(run.clipId(), runId, stage);
        try {
            run.heartbeat(stage, leaseId, workerId, clock.instant());
            runs.save(run, clock.instant());
            Instant until = run.snapshot().leaseExpiresAt();
            LOG.debug("heartbeat 로 lease 를 연장했다 worker={} leaseUntil={}", workerId, until);
            return until;
        } finally {
            clearJobContext();
        }
    }

    @Override
    @Transactional
    public int reclaim() {
        Instant now = clock.instant();
        int count = 0;
        for (PipelineRun run : runs.lockExpired(now.minusSeconds(15))) {
            if (run.reclaim(now)) {
                runs.save(run, now);
                count++;
            }
        }
        return count;
    }

    @Override
    @Transactional
    public Map<String, Object> attach(
            long runId,
            String workerId,
            UUID leaseId,
            Map<String, Object> transcript,
            List<Map<String, Object>> artifacts) {
        PipelineRun run = requireRun(runId);
        Instant now = clock.instant();
        run.fence("transcript_selection", leaseId, workerId, now);
        validateArtifacts(
                Map.of("artifacts", artifacts, "output", Map.of("transcript", transcript)),
                run.outputKeyPrefix("transcript_selection"));
        run.attachTranscript(transcript, artifacts);
        run.heartbeat("transcript_selection", leaseId, workerId, now);
        runs.save(run, now);
        return assignment(run, "transcript_selection");
    }

    /**
     * 워커 처리 구간 로그에 clip·run·stage 를 싣는다 (S15P21A501-204).
     *
     * <p>개별 키는 구조화 소비자(JSON appender 등)를 위한 것이고 {@code job} 은 표시용이다 — 텍스트 패턴에 개별 키를 나열하면 파이프라인과 무관한 모든 로그 줄에 빈 자리가 남는다.
     * 값이 없을 때 통째로 사라지도록 한 토큰으로 조립해 둔다.
     */
    private static void putJobContext(long clipId, long runId, String stage) {
        MDC.put("clipId", Long.toString(clipId));
        MDC.put("runId", Long.toString(runId));
        MDC.put("stage", stage);
        MDC.put("job", " clip=" + clipId + " run=" + runId + " stage=" + stage);
    }

    /** 스레드가 재사용되므로 구간을 벗어나면 반드시 지운다 ({@code RequestIdFilter} 와 같은 원칙). */
    private static void clearJobContext() {
        MDC.remove("clipId");
        MDC.remove("runId");
        MDC.remove("stage");
        MDC.remove("job");
    }

    private PipelineRun requireRun(long id) {
        return runs.lock(id).orElseThrow(() -> new BusinessException(JobErrorCode.NOT_FOUND));
    }

    private static void validateEnvelope(CompleteStageCommand command) {
        Map<String, Object> body = command.result();
        Map<String, Object> versions = JsonValues.object(body.get("versions"));
        String status = String.valueOf(body.get("status"));
        boolean success = "succeeded".equals(status);
        try {
            if (!"stage-result/v1".equals(body.get("envelopeVersion"))
                    || !PipelineStages.NAMES.contains(command.stage())
                    || !command.stage().equals(body.get("stage"))
                    || command.workerId() == null
                    || command.workerId().isBlank()
                    || command.idempotencyKey() == null
                    || !command.idempotencyKey().equals(body.get("idempotencyKey"))
                    || !List.of("succeeded", "failed", "skipped").contains(status)
                    || !(versions.get("stageVersion") instanceof String version)
                    || version.isBlank()
                    || !PipelineStages.outputSchema(command.stage()).equals(versions.get("outputSchemaVersion"))
                    || !versions.containsKey("modelVersion")
                    || !versions.containsKey("promptVersion")
                    || !(body.get("durationMs") instanceof Number duration)
                    || duration.longValue() < 0
                    || duration.doubleValue() != duration.longValue()
                    || !(body.get("attempt") instanceof Number attempt)
                    || attempt.intValue() < 1
                    || attempt.doubleValue() != attempt.intValue()) invalid();
            UUID.fromString((String) body.get("leaseId"));
            Instant start = Instant.parse((String) body.get("startedAt"));
            Instant finish = Instant.parse((String) body.get("finishedAt"));
            if (finish.isBefore(start)) invalid();
            if (success
                    && (JsonValues.object(body.get("output")).isEmpty()
                            || body.get("error") != null
                            || "unknown".equals(versions.get("stageVersion")))) invalid();
            if (!success) {
                Map<String, Object> error = JsonValues.object(body.get("error"));
                if (!(error.get("code") instanceof String code)
                        || code.isBlank()
                        || code.length() > 64
                        || !(error.get("retryable") instanceof Boolean)) invalid();
            }
            if (success && command.stage().equals("transcript_selection")) {
                var transcript =
                        JsonValues.object(JsonValues.object(body.get("output")).get("transcript"));
                if (!(transcript.get("asrRequired") instanceof Boolean)
                        || !(transcript.get("candidateRanges") instanceof List<?>)
                        || !List.of("SUBTITLE_COVERED", "UNCOVERED_RANGES", "NO_VALID_SUBTITLE")
                                .contains(transcript.get("reasonCode"))) invalid();
                if (Boolean.FALSE.equals(transcript.get("asrRequired"))
                        && (!"SUBTITLE_COVERED".equals(transcript.get("reasonCode"))
                                || !((List<?>) transcript.get("candidateRanges")).isEmpty())) invalid();
            }
            if (success
                    && command.stage().equals("asr")
                    && !(JsonValues.object(body.get("output")).get("segments") instanceof List<?>)) invalid();
        } catch (ClassCastException | NullPointerException | IllegalArgumentException failure) {
            throw new BusinessException(PipelineErrorCode.INVALID_RESULT, failure);
        }
    }

    private static void invalid() {
        throw new BusinessException(PipelineErrorCode.INVALID_RESULT);
    }

    private static void validateArtifacts(Map<String, Object> body, String prefix) {
        Object value = body.get("artifacts");
        if (value != null && !(value instanceof List<?>)) invalid();
        List<?> artifacts = value == null ? List.of() : (List<?>) value;
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Object item : artifacts) {
            Map<String, Object> artifact = JsonValues.object(item);
            if (!(artifact.get("storageKey") instanceof String key)
                    || !key.startsWith(prefix)
                    || key.length() <= prefix.length()
                    || key.contains("\\")
                    || key.contains(":")
                    || java.util.Arrays.asList(key.split("/")).contains(".."))
                throw new BusinessException(JobErrorCode.ARTIFACT_PATH_FORBIDDEN);
            if (!(artifact.get("contentHash") instanceof String hash)
                    || !hash.matches("[0-9a-f]{64}")
                    || !(artifact.get("byteSize") instanceof Number size)
                    || size.longValue() < 0
                    || size.doubleValue() != size.longValue()
                    || !(artifact.get("kind") instanceof String kind)
                    || kind.isBlank()
                    || !keys.add(key)) invalid();
        }
        var transcript = JsonValues.object(JsonValues.object(body.get("output")).get("transcript"));
        for (String name : List.of("segmentsArtifact", "decisionsArtifact")) {
            Object reference = transcript.get(name);
            if (reference != null && !artifacts.contains(reference)) invalid();
        }
    }
}
