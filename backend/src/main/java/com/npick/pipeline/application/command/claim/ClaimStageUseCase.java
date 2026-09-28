package com.npick.pipeline.application.command.claim;

import java.util.Map;
import java.util.Optional;

public interface ClaimStageUseCase {
    Optional<Map<String, Object>> claim(ClaimStageCommand command);
}
