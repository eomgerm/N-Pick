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
        long registeredById,
        com.npick.member.application.query.MemberSummary registeredBy,
        Run latestRun,
        com.npick.pipeline.application.query.ProcessingDetailsResult processingDetails) {
    public ClipQueryResult withRegisteredBy(com.npick.member.application.query.MemberSummary registrant) {
        return new ClipQueryResult(
                clipId,
                title,
                sourceType,
                activePipelineRunId,
                createdAt,
                updatedAt,
                defaultTranscriptSource,
                hasSubtitle,
                hasScript,
                registeredById,
                registrant,
                latestRun,
                processingDetails);
    }

    public record Run(
            long pipelineRunId,
            int processingNo,
            String status,
            String errorCode,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt) {}
}
