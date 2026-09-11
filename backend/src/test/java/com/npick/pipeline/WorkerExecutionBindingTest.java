package com.npick.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.command.WorkerExecutionBinding;
import com.npick.pipeline.application.command.claim.ClaimStageCommand;
import com.npick.pipeline.application.command.claim.ClaimStageUseCase;
import com.npick.pipeline.application.port.StageOutputPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
}
