package com.npick.pipeline.application.command.complete;

import java.util.Map;

import com.npick.pipeline.domain.model.JsonValues;

public record CompleteStageCommand(
        long runId, String stage, String workerId, String idempotencyKey, Map<String, Object> result) {
    public CompleteStageCommand {
        result = JsonValues.copy(result);
    }
}
