package com.npick.pipeline.application.command.claim;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AttachStageInputUseCase {
    Map<String, Object> attach(
            long runId,
            String workerId,
            UUID leaseId,
            Map<String, Object> transcript,
            List<Map<String, Object>> artifacts);
}
