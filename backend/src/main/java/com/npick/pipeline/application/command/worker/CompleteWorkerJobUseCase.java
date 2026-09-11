package com.npick.pipeline.application.command.worker;

import java.util.Map;

public interface CompleteWorkerJobUseCase {
    Map<String, Object> complete(long run, String stage, String worker, String key, Map<String, Object> body);
}
