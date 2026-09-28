package com.npick.clip.infrastructure.worker;

import org.springframework.stereotype.Component;

import com.npick.clip.application.command.activate.ActivateProcessedClipUseCase;
import com.npick.pipeline.application.port.ProcessedClipActivationPort;

/** {@link ProcessedClipActivationPort} 구현 (S15P21A501-201). 게시 판정은 clip 의 UseCase 가 그대로 한다. */
@Component
public class WorkerClipActivationAdapter implements ProcessedClipActivationPort {
    private final ActivateProcessedClipUseCase activation;

    public WorkerClipActivationAdapter(ActivateProcessedClipUseCase activation) {
        this.activation = activation;
    }

    @Override
    public boolean activate(long clipId, long pipelineRunId, int processingNo) {
        return activation.activate(clipId, pipelineRunId, processingNo);
    }
}
