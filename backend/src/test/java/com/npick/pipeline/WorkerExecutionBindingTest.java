package com.npick.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.command.WorkerExecutionBinding;
import com.npick.pipeline.application.command.claim.ClaimStageCommand;
import com.npick.pipeline.application.command.claim.ClaimStageUseCase;
import com.npick.pipeline.application.command.heartbeat.HeartbeatStageUseCase;
import com.npick.pipeline.application.port.StageOutputPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WorkerExecutionBindingTest {
    @Test
    void ignoresUnknownAndUnpersistableCapabilitiesButRejectsDuplicates() {
        var claims = mock(ClaimStageUseCase.class);
        var outputs = mock(StageOutputPort.class);
        when(claims.claim(any())).thenReturn(Optional.empty());
        when(outputs.supports("scene_detection")).thenReturn(true);
        var binding = new WorkerExecutionBinding(claims, null, null, null, null, null, outputs);
        var known = Map.of("stage", "scene_detection", "stageVersion", "v1");
        var future = Map.of("stage", "future_stage", "stageVersion", "v1");
        binding.claim(
                "worker",
                Map.of(
                        "capabilities",
                        List.of(known, future, Map.of("stage", "ocr", "stageVersion", "v1")),
                        "device",
                        Map.of()));
        var captured = ArgumentCaptor.forClass(ClaimStageCommand.class);
        verify(claims).claim(captured.capture());
        assertThat(captured.getValue().capabilities()).containsExactlyEntriesOf(Map.of("scene_detection", "v1"));
        assertThatThrownBy(() -> binding.claim("worker", Map.of("capabilities", List.of(future, future))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("JOB_400_001"));
        verifyNoMoreInteractions(claims);
    }

    /**
     * 계약 §4.2 — 200 응답은 {@code command:"continue"} 와 {@code leaseUntil} 둘뿐이고 {@code abortReason} 키는 싣지 않는다. 회수는 409
     * {@code JOB_409_002} 단일 경로이며(StageExecutionIntegrationTest), {@code abort} 는 아직 낼 사유가 없는 예약 어휘다. 이 단언이 없으면 계약이 구현과
     * 다시 어긋나도 아무도 모른다 (S15P21A501-199).
     */
    @Test
    void heartbeatAnswersContinueAndCarriesNoAbortVocabulary() {
        var heartbeats = mock(HeartbeatStageUseCase.class);
        Instant until = Instant.parse("2026-09-07T09:22:19Z");
        when(heartbeats.heartbeat(anyLong(), anyString(), anyString(), any())).thenReturn(until);
        var binding = new WorkerExecutionBinding(null, heartbeats, null, null, null, null, null);

        var response = binding.heartbeat(
                7L,
                "scene_detection",
                "worker-1",
                Map.of("leaseId", UUID.randomUUID().toString()));

        assertThat(response).containsOnly(entry("command", "continue"), entry("leaseUntil", until.toString()));
    }
}
