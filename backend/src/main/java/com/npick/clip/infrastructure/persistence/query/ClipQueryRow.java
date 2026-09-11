package com.npick.clip.infrastructure.persistence.query;

import java.time.Instant;

import com.npick.clip.application.query.ClipQueryResult;

public record ClipQueryRow(
        Long clipId,
        String title,
        String sourceType,
        Long activeRunId,
        Instant createdAt,
        Instant updatedAt,
        String transcriptSource,
        Boolean hasSubtitle,
        Boolean hasScript,
        Long runId,
        Integer processingNo,
        String status,
        String errorCode,
        Instant runCreatedAt,
        Instant startedAt,
        Instant finishedAt) {
    public ClipQueryResult toResult() {
        return new ClipQueryResult(
                clipId,
                title,
                sourceType,
                activeRunId,
                createdAt,
                updatedAt,
                transcriptSource,
                hasSubtitle,
                hasScript,
                runId == null
                        ? null
                        : new ClipQueryResult.Run(
                                runId, processingNo, status, errorCode, runCreatedAt, startedAt, finishedAt),
                null);
    }
}
