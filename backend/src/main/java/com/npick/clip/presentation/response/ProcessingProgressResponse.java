package com.npick.clip.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import com.npick.pipeline.application.query.ProcessingProgressResult;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(
        description =
                "최신 run의 저장된 단계 상태 요약. 상세와 동일한 record_status. run이 없으면 progress 자체가 null. 부분/미확인 기록의 단계 수는 null이며 백분율·남은 시간을 추정하지 않는다.")
public record ProcessingProgressResponse(
        @JsonProperty("record_status") String recordStatus,

        @Schema(description = "running인 단계가 정확히 하나일 때 그 이름. 실행 전·종료·미확인 또는 여러 running 단계이면 null.", nullable = true)
        @JsonProperty("current_stage")
        String currentStage,

        @JsonProperty("total_steps") Integer totalSteps,
        @JsonProperty("succeeded_steps") Integer succeededSteps,
        @JsonProperty("skipped_steps") Integer skippedSteps,

        @Schema(
                description = "status=failed인 단계 수. skipped는 skipped_steps에만 포함되며 필수 단계 생략으로 run이 실패할 수도 있다.",
                nullable = true)
        @JsonProperty("failed_steps")
        Integer failedSteps) {
    static ProcessingProgressResponse from(ProcessingProgressResult value) {
        return value == null
                ? null
                : new ProcessingProgressResponse(
                        value.recordStatus(),
                        value.currentStage(),
                        value.totalSteps(),
                        value.succeededSteps(),
                        value.skippedSteps(),
                        value.failedSteps());
    }
}
