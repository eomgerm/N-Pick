package com.npick.pipeline.application.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.transaction.support.TransactionTemplate;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.command.claim.ClaimStageCommand;
import com.npick.pipeline.application.command.claim.ClaimStageUseCase;
import com.npick.pipeline.application.command.complete.CompleteStageCommand;
import com.npick.pipeline.application.command.complete.CompleteStageUseCase;
import com.npick.pipeline.application.command.heartbeat.HeartbeatStageUseCase;
import com.npick.pipeline.application.error.JobErrorCode;
import com.npick.pipeline.application.port.ClipMediaInputPort;
import com.npick.pipeline.application.port.WorkerExecutionPort;
import com.npick.pipeline.domain.error.PipelineErrorCode;
import com.npick.pipeline.domain.model.JsonValues;
import com.npick.pipeline.domain.model.PipelineStages;
import com.npick.pipeline.domain.repository.PipelineRunRepository;

/** Wire adaptation only: assignment, state changes and completion remain in the existing executor. */
public class WorkerExecutionBinding implements WorkerExecutionPort {
    private final ClaimStageUseCase claims;
    private final HeartbeatStageUseCase heartbeats;
    private final CompleteStageUseCase completions;
    private final PipelineRunRepository runs;
    private final ClipMediaInputPort media;
    private final TransactionTemplate transaction;
    private final com.npick.pipeline.application.port.StageOutputPort outputs;

    public WorkerExecutionBinding(
            ClaimStageUseCase claims,
            HeartbeatStageUseCase heartbeats,
            CompleteStageUseCase completions,
            PipelineRunRepository runs,
            ClipMediaInputPort media,
            TransactionTemplate transaction,
            com.npick.pipeline.application.port.StageOutputPort outputs) {
        this.claims = claims;
        this.heartbeats = heartbeats;
        this.completions = completions;
        this.runs = runs;
        this.media = media;
        this.transaction = transaction;
        this.outputs = outputs;
    }

    public Map<String, Object> claim(String worker, Map<String, Object> request) {
        var capabilities = new LinkedHashMap<String, String>();
        if (!(request.get("capabilities") instanceof List<?> items)) throw invalid();
        for (Object item : items) {
            var capability = JsonValues.object(item);
            if (!(capability.get("stage") instanceof String stage)
                    || stage.isBlank()
                    || !(capability.get("stageVersion") instanceof String version)
                    || version.isBlank()
                    || capabilities.putIfAbsent(stage, version) != null) throw invalid();
        }
        // Independent deployments may advertise stages the BE cannot yet persist.
        capabilities.keySet().removeIf(stage -> !PipelineStages.NAMES.contains(stage) || !outputs.supports(stage));
        List<String> revoked = new ArrayList<>();
        Object held = request.getOrDefault("heldLeases", List.of());
        if (!(held instanceof List<?> leases)) throw invalid();
        for (Object value : leases) {
            var lease = JsonValues.object(value);
            long runId;
            UUID leaseId = uuid(lease.get("leaseId"));
            try {
                runId = Long.parseLong((String) lease.get("pipelineRunId"));
            } catch (RuntimeException failure) {
                throw invalid();
            }
            boolean valid = Boolean.TRUE.equals(transaction.execute(status -> {
                var run = runs.lock(runId);
                if (run.isEmpty()) return false;
                var snapshot = run.orElseThrow().snapshot();
                return leaseId.equals(snapshot.leaseId())
                        && worker.equals(snapshot.workerId())
                        && java.util.Objects.equals(lease.get("stage"), snapshot.leaseStage())
                        && Instant.now().isBefore(snapshot.leaseExpiresAt());
            }));
            if (!valid) revoked.add(leaseId.toString());
        }
        var response = new LinkedHashMap<>(
                claims.claim(new ClaimStageCommand(worker, capabilities, JsonValues.object(request.get("device"))))
                        .orElse(Map.of("assigned", false)));
        response.put("revokedLeases", revoked);
        return response;
    }

    public Map<String, Object> heartbeat(long run, String stage, String worker, Map<String, Object> request) {
        Instant until = heartbeats.heartbeat(run, stage, worker, uuid(request.get("leaseId")));
        return Map.of("command", "continue", "leaseUntil", until.toString());
    }

    public Map<String, Object> complete(long run, String stage, String worker, String key, Map<String, Object> result) {
        return completions.complete(new CompleteStageCommand(run, stage, worker, key, result));
    }

    public <T> T withArtifactAccess(long runId, String worker, UUID lease, Function<ArtifactAccess, T> operation) {
        return transaction.execute(status -> {
            var run = runs.lock(runId).orElseThrow(() -> new BusinessException(JobErrorCode.NOT_FOUND));
            var snapshot = run.snapshot();
            run.fence(snapshot.leaseStage(), lease, worker, Instant.now());
            Set<String> readable = new HashSet<>();
            readable.add(media.get(snapshot.clipId()).storageKey());
            // Only references accepted by the executor's prefix validation are authoritative.
            // Arbitrary extra output fields must never grant access to another run's files.
            for (String stage : PipelineStages.NAMES) {
                var state = run.state(stage);
                if ("succeeded".equals(state.get("status"))) references(state.get("artifacts"), readable);
            }
            references(run.state(snapshot.leaseStage()).get("inputArtifacts"), readable);
            return operation.apply(new ArtifactAccess(run.outputKeyPrefix(snapshot.leaseStage()), readable));
        });
    }

    private static void references(Object value, Set<String> keys) {
        if (!(value instanceof List<?> list)) return;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map
                    && map.get("storageKey") instanceof String key
                    && map.containsKey("contentHash")
                    && map.containsKey("byteSize")) keys.add(key);
        }
    }

    private static UUID uuid(Object value) {
        try {
            return UUID.fromString((String) value);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(PipelineErrorCode.INVALID_RESULT);
    }
}
