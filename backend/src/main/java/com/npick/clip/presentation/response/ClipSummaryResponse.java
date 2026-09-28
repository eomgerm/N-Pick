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
        @JsonProperty("registered_by") RegistrantResponse registeredBy,
        @JsonProperty("latest_run") RunResponse latestRun,
        ProcessingProgressResponse progress) {
    public static ClipSummaryResponse from(ClipQueryResult result) {
        return from(
                result, com.npick.pipeline.application.query.ProcessingProgressResult.from(result.processingDetails()));
    }

    public static ClipSummaryResponse from(
            ClipQueryResult result, com.npick.pipeline.application.query.ProcessingProgressResult progress) {
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
                RegistrantResponse.from(result.registeredBy()),
                RunResponse.from(result.latestRun()),
                ProcessingProgressResponse.from(progress));
    }

    /** 공개 범위는 로그인 ID 까지다. 내부 식별자 registered_by_id 는 응답에 싣지 않는다 (docs/contracts/web-api.md §6.5). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RegistrantResponse(
            @JsonProperty("login_id") String loginId) {
        static RegistrantResponse from(com.npick.member.application.query.MemberSummary registrant) {
            return registrant == null ? null : new RegistrantResponse(registrant.loginId());
        }
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
