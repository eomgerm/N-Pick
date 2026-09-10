package com.npick.pipeline.application.command.heartbeat;

import java.time.Instant;
import java.util.UUID;

public interface HeartbeatStageUseCase {
    Instant heartbeat(long runId, String stage, String workerId, UUID leaseId);
}
