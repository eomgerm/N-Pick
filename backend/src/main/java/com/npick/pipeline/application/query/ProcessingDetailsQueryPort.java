package com.npick.pipeline.application.query;

public interface ProcessingDetailsQueryPort {
    java.util.Map<Long, ProcessingProgressResult> findProgress(java.util.List<Long> runIds);

    ProcessingDetailsResult find(long clipId, long pipelineRunId);
}
