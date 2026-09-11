package com.npick.pipeline.application.query;

public interface ProcessingDetailsQueryPort {
    ProcessingDetailsResult find(long clipId, long pipelineRunId);
}
