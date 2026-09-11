package com.npick.pipeline.application.command.worker;

import java.util.Map;

public interface ClaimWorkerJobUseCase {
    Map<String, Object> claim(String worker, Map<String, Object> body);
}
