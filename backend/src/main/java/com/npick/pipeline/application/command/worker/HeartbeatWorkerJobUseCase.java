package com.npick.pipeline.application.command.worker;

import java.util.Map;

public interface HeartbeatWorkerJobUseCase {
    Map<String, Object> heartbeat(long run, String stage, String worker, Map<String, Object> body);
}
