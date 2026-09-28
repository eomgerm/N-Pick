package com.npick.pipeline.application.query;

import java.time.Instant;
import java.util.List;

/** 공개 가능한 처리 기록의 projection. 내부 JSON, 원문, 파일 참조를 포함하지 않는다. */
public record ProcessingDetailsResult(
        long pipelineRunId,
        String recordStatus,
        List<Stage> stages,
        List<String> failedStages,
        List<String> missingChannels,
        Transcript transcript) {
    public record Stage(
            String name,
            String status,
            Integer attempts,
            Instant startedAt,
            Instant finishedAt,
            String errorCode,
            String reasonCode,
            Boolean automaticRetryable,
            Integer maxAttempts,
            List<FailedAttempt> failedAttempts) {}

    public record FailedAttempt(Integer attempt, String errorCode, Instant finishedAt) {}

    public record Transcript(
            String recordStatus,
            String selectionStage,
            List<String> usedSources,
            List<String> adoptionReasons,
            String representativeSource,
            Boolean asrRequired,
            String selectionReason,
            String asrStatus,
            String asrReason,
            Integer asrSegmentCount,
            String embeddedStatus) {}
}
