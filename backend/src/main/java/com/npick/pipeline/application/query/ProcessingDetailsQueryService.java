package com.npick.pipeline.application.query;

import org.springframework.stereotype.Service;

@Service
public class ProcessingDetailsQueryService implements GetProcessingDetailsUseCase, GetProcessingProgressUseCase {
    private final ProcessingDetailsQueryPort records;

    public ProcessingDetailsQueryService(ProcessingDetailsQueryPort records) {
        this.records = records;
    }

    @Override
    public java.util.Map<Long, ProcessingProgressResult> getProgress(java.util.List<Long> runIds) {
        return records.findProgress(runIds);
    }

    @Override
    public ProcessingDetailsResult getProcessingDetails(long clipId, long pipelineRunId) {
        return records.find(clipId, pipelineRunId);
    }
}
