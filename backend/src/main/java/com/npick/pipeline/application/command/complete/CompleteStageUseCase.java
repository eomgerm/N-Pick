package com.npick.pipeline.application.command.complete;

import java.util.Map;

public interface CompleteStageUseCase {
    Map<String, Object> complete(CompleteStageCommand command);
}
