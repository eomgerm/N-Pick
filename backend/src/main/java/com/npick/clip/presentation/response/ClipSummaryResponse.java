package com.npick.clip.presentation.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.clip.application.query.ClipQueryResult;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClipSummaryResponse(
        @JsonProperty("clip_id") String clipId,
        String title,
        @JsonProperty("source_type") String sourceType,
        @JsonProperty("search_available") boolean searchAvailable,
        @JsonProperty("active_pipeline_run_id") String activePipelineRunId,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("latest_run") RunResponse latestRun) {
    public static ClipSummaryResponse from(ClipQueryResult result) {
        return new ClipSummaryResponse(
                Long.toString(result.clipId()),
                result.title(),
                result.sourceType(),
                result.activePipelineRunId() != null,
                result.activePipelineRunId() == null
                        ? null
                        : result.activePipelineRunId().toString(),
                result.createdAt(),
                result.updatedAt(),
                RunResponse.from(result.latestRun()));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunResponse(
            @JsonProperty("pipeline_run_id") String pipelineRunId,
            @JsonProperty("processing_no") int processingNo,
            String status,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("finished_at") Instant finishedAt) {
        static RunResponse from(ClipQueryResult.Run run) {
            if (run == null) return null;
            // Only machine-code syntax is public; free-form diagnostics may contain server paths.
            String code = run.errorCode();
            if (code != null && !code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) code = null;
            return new RunResponse(
                    Long.toString(run.pipelineRunId()),
                    run.processingNo(),
                    run.status(),
                    code,
                    run.createdAt(),
                    run.startedAt(),
                    run.finishedAt());
        }
    }
}
