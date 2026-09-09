package com.npick.clip.presentation.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.clip.application.command.register.RegisterClipResult;

/** bigint TSID는 브라우저의 정수 정밀도 손실을 피하도록 문자열로 응답한다. */
public record ClipRegistrationResponse(
        @JsonProperty("clip_id") String clipId,
        @JsonProperty("pipeline_run_id") String pipelineRunId,
        String status) {
    public static ClipRegistrationResponse from(RegisterClipResult result) {
        return new ClipRegistrationResponse(
                Long.toString(result.clipId()), Long.toString(result.pipelineRunId()), result.status());
    }
}
