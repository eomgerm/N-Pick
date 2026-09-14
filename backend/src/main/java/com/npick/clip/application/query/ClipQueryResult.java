package com.npick.clip.application.query;

import java.time.Instant;

public record ClipQueryResult(
        long clipId,
        String title,
        String sourceType,
        Long activePipelineRunId,
        Instant createdAt,
        Instant updatedAt,
        String defaultTranscriptSource,
        boolean hasSubtitle,
        boolean hasScript,
        Run latestRun,
        com.npick.pipeline.application.query.ProcessingDetailsResult processingDetails) {
    public record Run(
            long pipelineRunId,
            int processingNo,
            String status,
            String errorCode,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt) {}
}
