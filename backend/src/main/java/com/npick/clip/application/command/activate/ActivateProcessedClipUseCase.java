package com.npick.clip.application.command.activate;

public interface ActivateProcessedClipUseCase {
    boolean activate(long clipId, long pipelineRunId, int processingNo);
}
