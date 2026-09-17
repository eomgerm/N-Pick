package com.npick.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.command.StageExecutionService;
import com.npick.pipeline.application.command.complete.CompleteStageCommand;
import com.npick.pipeline.application.command.worker.ClaimWorkerJobUseCase;
import com.npick.pipeline.domain.model.PipelineRun;
import com.npick.pipeline.domain.model.PipelineStages;
import com.npick.pipeline.domain.repository.PipelineRunRepository;
import com.npick.pipeline.presentation.WorkerJobController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 워커 처리 구간의 로그 상관키 (S15P21A501-204). */
class WorkerJobLoggingContextTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    /**
     * claim 은 롱폴이라 본체가 요청 스레드가 아닌 새 가상 스레드에서 돈다. MDC 는 스레드 로컬이므로 넘겨주지 않으면 이 구간 로그가 요청과 끊긴다 — 필터가 값을 지우는 시점도 롱폴 종료보다
     * 앞선다.
     */
    @Test
    void claimCarriesTheRequestContextIntoTheVirtualThread() throws Exception {
        var seen = new AtomicReference<Map<String, String>>();
        var completed = new CountDownLatch(1);
        ClaimWorkerJobUseCase claims = (worker, body) -> {
            seen.set(MDC.getCopyOfContextMap());
            return Map.of("assigned", false);
        };
        var controller = new WorkerJobController(claims, null, null, null, null);

        MDC.put("requestId", "req-1");
        var result = controller.claim("worker-1", Map.of());
        // 롱폴 응답이 실제로 채워진 뒤에 본다 — 스텁 진입만 기다리면 setResult 와 경합한다.
        result.setResultHandler(value -> completed.countDown());

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).containsEntry("requestId", "req-1");
    }

    /** heartbeat 은 풀에서 재사용되는 요청 스레드에서 돈다. 구간을 벗어나고도 값이 남으면 다음 요청 로그가 남의 clip 을 달고 나간다. */
    @Test
    void heartbeatPublishesJobContextAndClearsItAfterwards() {
        var runs = mock(PipelineRunRepository.class);
        var during = new AtomicReference<Map<String, String>>();
        UUID lease = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        var run = new PipelineRun(snapshot(7L, 42L, lease, "ocr", "worker-1", now.plusSeconds(30)));
        when(runs.lock(7L)).thenReturn(Optional.of(run));
        doAnswer(invocation -> {
                    during.set(MDC.getCopyOfContextMap());
                    return null;
                })
                .when(runs)
                .save(any(), any());
        var service = new StageExecutionService(runs, null, null, null, Clock.fixed(now, ZoneOffset.UTC), null);

        service.heartbeat(7L, "ocr", "worker-1", lease);

        assertThat(during.get())
                .containsEntry("clipId", "42")
                .containsEntry("runId", "7")
                .containsEntry("stage", "ocr")
                // application.yml 의 logging.pattern.level 이 %X{job:-} 으로 읽는 이름이다.
                // 키가 어긋나면 값이 MDC 에 있어도 로그에는 한 글자도 안 나온다.
                .containsEntry("job", " clip=42 run=7 stage=ocr");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    /**
     * complete 는 셋 중 가장 복잡한 경로다. run 을 읽기도 전에 나는 실패(봉투 검증·조회 실패)에도 상관키가 남아야 하고 — 전역 예외 핸들러의 로그는 이 구간 밖에서 찍히므로 여기서 남기지
     * 않으면 아무 데도 없다 — 그 뒤 스레드에는 아무것도 남으면 안 된다.
     */
    @Test
    void completePublishesJobContextBeforeTheRunIsLoadedAndClearsItAfterwards() {
        var runs = mock(PipelineRunRepository.class);
        var during = new AtomicReference<Map<String, String>>();
        when(runs.lock(7L)).thenAnswer(invocation -> {
            during.set(MDC.getCopyOfContextMap());
            // 여기서 끝내면 저장 경로를 건드리지 않고 실패 구간만 본다.
            return Optional.empty();
        });
        var service = new StageExecutionService(runs, null, null, null, Clock.systemUTC(), null);

        assertThatThrownBy(() ->
                        service.complete(new CompleteStageCommand(7L, "ocr", "worker-1", "key-1", failedResult())))
                .isInstanceOf(BusinessException.class);

        assertThat(during.get()).containsEntry("runId", "7").containsEntry("stage", "ocr");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private static Map<String, Object> failedResult() {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("stageVersion", "v1");
        versions.put("outputSchemaVersion", PipelineStages.outputSchema("ocr"));
        versions.put("modelVersion", null);
        versions.put("promptVersion", null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("envelopeVersion", "stage-result/v1");
        result.put("stage", "ocr");
        result.put("idempotencyKey", "key-1");
        result.put("leaseId", UUID.randomUUID().toString());
        result.put("status", "failed");
        result.put("attempt", 1);
        result.put("durationMs", 10);
        result.put("startedAt", "2026-09-17T00:00:00Z");
        result.put("finishedAt", "2026-09-17T00:00:01Z");
        result.put("versions", versions);
        result.put("error", Map.of("code", "STAGE_FAILED", "retryable", true));
        return result;
    }

    private static PipelineRun.Snapshot snapshot(
            long id, long clipId, UUID lease, String stage, String worker, Instant expires) {
        Map<String, Object> stages = new LinkedHashMap<>();
        for (String name : PipelineStages.NAMES) {
            stages.put(name, new LinkedHashMap<>(Map.of("status", "pending", "attempts", 0)));
        }
        stages.put(stage, new LinkedHashMap<>(Map.of("status", "running", "attempts", 1)));
        return new PipelineRun.Snapshot(
                id, clipId, 1, "v1", "running", null, null, null, lease, stage, worker, expires, null, stages);
    }
}
