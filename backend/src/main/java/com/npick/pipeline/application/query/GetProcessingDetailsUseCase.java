package com.npick.pipeline.application.query;

public interface GetProcessingDetailsUseCase {
    ProcessingDetailsResult getProcessingDetails(long clipId, long pipelineRunId);
}
