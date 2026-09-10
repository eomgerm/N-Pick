package com.npick.pipeline.infrastructure.config;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import com.npick.pipeline.application.command.heartbeat.HeartbeatStageUseCase;
import com.npick.pipeline.application.port.PreparationLeasePort;

/** 아직 워커에 보내지 않은 입력 준비의 lease만 갱신한다. 추출을 취소하지 못해도 attach의 fencing은 유지한다. */
@Component
public class PreparationLeaseHeartbeat implements PreparationLeasePort {
    private final HeartbeatStageUseCase heartbeat;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            1,
            Thread.ofPlatform()
                    .daemon(true)
                    .name("transcript-preparation-heartbeat-", 0)
                    .factory());

    public PreparationLeaseHeartbeat(HeartbeatStageUseCase heartbeat) {
        this.heartbeat = heartbeat;
    }

    public Guard maintain(long runId, String stage, String workerId, UUID leaseId) {
        AtomicReference<RuntimeException> failed = new AtomicReference<>();
        var task = scheduler.scheduleAtFixedRate(
                () -> {
                    if (failed.get() != null) return;
                    try {
                        heartbeat.heartbeat(runId, stage, workerId, leaseId);
                    } catch (RuntimeException failure) {
                        failed.compareAndSet(null, failure);
                    }
                },
                10,
                10,
                TimeUnit.SECONDS);
        return new Guard() {
            public void verify() {
                if (failed.get() != null) throw failed.get();
            }

            public void close() {
                task.cancel(false);
            }
        };
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
