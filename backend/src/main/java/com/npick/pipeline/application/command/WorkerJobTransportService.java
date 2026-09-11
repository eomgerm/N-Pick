package com.npick.pipeline.application.command;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.error.WorkerIntegrationErrorCode;
import com.npick.pipeline.application.port.WorkerArtifactPort;
import com.npick.pipeline.application.port.WorkerExecutionPort;

/** Live and offline clients use the same completion and fenced artifact paths. */
public final class WorkerJobTransportService
        implements com.npick.pipeline.application.command.worker.ClaimWorkerJobUseCase,
                com.npick.pipeline.application.command.worker.HeartbeatWorkerJobUseCase,
                com.npick.pipeline.application.command.worker.CompleteWorkerJobUseCase,
                com.npick.pipeline.application.command.worker.UploadWorkerArtifactUseCase,
                com.npick.pipeline.application.command.worker.DownloadWorkerArtifactUseCase {
    private final WorkerExecutionPort execution;
    private final WorkerArtifactPort artifacts;
    private final String fleet;
    private final WorkerInputAssembler inputs;

    public WorkerJobTransportService(
            WorkerExecutionPort execution, WorkerArtifactPort artifacts, String fleet, WorkerInputAssembler inputs) {
        this.execution = execution;
        this.artifacts = artifacts;
        this.fleet = fleet;
        this.inputs = inputs;
    }

    public Map<String, Object> claim(String worker, Map<String, Object> body) {
        if (!(body.get("worker") instanceof Map<?, ?> identity) || !worker.equals(identity.get("workerId")))
            throw new BusinessException(WorkerIntegrationErrorCode.INVALID_OUTPUT);
        if (!fleet.equals(identity.get("fleet")))
            throw new BusinessException(WorkerIntegrationErrorCode.FLEET_FORBIDDEN);
        if (!(body.get("waitSeconds") instanceof Number wait)
                || wait.intValue() < 0
                || wait.intValue() > 25
                || wait.doubleValue() != wait.intValue())
            throw new BusinessException(WorkerIntegrationErrorCode.INVALID_OUTPUT);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(wait.intValue());
        while (true) {
            Map<String, Object> response = execution.claim(worker, body);
            if (Boolean.TRUE.equals(response.get("assigned")))
                return inputs.attach(response, Boolean.TRUE.equals(identity.get("sharedMediaVolume")));
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) return response;
            // This service holds no DB transaction; the short execution-port call has returned.
            java.util.concurrent.locks.LockSupport.parkNanos(Math.min(remaining, 500_000_000L));
        }
    }

    public Map<String, Object> heartbeat(long run, String stage, String worker, Map<String, Object> body) {
        return execution.heartbeat(run, stage, worker, body);
    }

    public Map<String, Object> complete(long run, String stage, String worker, String key, Map<String, Object> body) {
        Map<String, Object> result = execution.complete(run, stage, worker, key, body);
        if (!(result.get("next") instanceof Map<?, ?> next)) return result;
        Map<String, Object> assignment = new java.util.LinkedHashMap<>();
        next.forEach((name, value) -> assignment.put((String) name, value));
        var response = new java.util.LinkedHashMap<>(result);
        // HTTP is always valid when the completing request has no volume capability declaration.
        response.put("next", inputs.attach(assignment, false));
        return response;
    }

    public void upload(long run, String worker, UUID lease, String key, long size, String hash, InputStream input) {
        String prefix = execution.withArtifactAccess(run, worker, lease, access -> access.outputKeyPrefix());
        // Receive and verify bytes without holding the run lock; heartbeat can continue.
        try (var prepared = artifacts.prepareUpload(prefix, key, size, hash, input)) {
            execution.withArtifactAccess(run, worker, lease, access -> {
                if (!prefix.equals(access.outputKeyPrefix()))
                    throw new BusinessException(WorkerIntegrationErrorCode.PATH_FORBIDDEN);
                prepared.publish();
                return null;
            });
        }
    }

    public void download(long run, String worker, UUID lease, String key, OutputStream output) {
        execution.withArtifactAccess(run, worker, lease, access -> {
            if (!access.readableKeys().contains(key))
                throw new BusinessException(WorkerIntegrationErrorCode.PATH_FORBIDDEN);
            return null;
        });
        // Read permission is checked at request start. Immutable inputs may stream without a DB lock.
        artifacts.download(key, output);
    }
}
