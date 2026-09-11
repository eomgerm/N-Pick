package com.npick.pipeline.application.command.claim;

import java.util.Map;

import com.npick.pipeline.domain.model.JsonValues;

public record ClaimStageCommand(String workerId, Map<String, String> capabilities, Map<String, Object> device) {
    public ClaimStageCommand {
        if (workerId == null || workerId.isBlank() || workerId.length() > 64)
            throw new IllegalArgumentException("workerId");
        capabilities = Map.copyOf(capabilities);
        device = JsonValues.copy(device);
    }
}
