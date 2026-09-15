package com.npick.clip.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.clip.application.query.ClipQueryResult;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClipDetailResponse(
        ClipSummaryResponse clip,

        @io.swagger.v3.oas.annotations.media.Schema(
                description = "활성 run 공개 시 저장된 대표 출처. 활성 run이 없으면 none; provided/asr/none")
        @JsonProperty("default_transcript_source")
        String defaultTranscriptSource,

        @JsonProperty("has_subtitle") boolean hasSubtitle,
        @JsonProperty("has_script") boolean hasScript,
        @JsonProperty("processing_details") ProcessingDetailsResponse processingDetails) {
    public static ClipDetailResponse from(ClipQueryResult result) {
        return new ClipDetailResponse(
                ClipSummaryResponse.from(result),
                result.activePipelineRunId() == null ? "none" : result.defaultTranscriptSource(),
                result.hasSubtitle(),
                result.hasScript(),
                ProcessingDetailsResponse.from(result.processingDetails()));
    }
}
