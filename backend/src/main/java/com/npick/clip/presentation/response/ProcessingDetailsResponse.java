package com.npick.clip.presentation.response;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import com.npick.pipeline.application.query.ProcessingDetailsResult;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "최신 처리 시도의 기록. pipeline_run_id는 latest_run과 같으며 활성 검색 결과의 상태가 아니다. null 값은 기록/판정 미확인을 뜻한다.")
public record ProcessingDetailsResponse(
        @JsonProperty("pipeline_run_id") String pipelineRunId,

        @Schema(allowableValues = {"available", "partial", "legacy", "unavailable", "unsupported_version"})
        @JsonProperty("record_status")
        String recordStatus,

        List<StageResponse> stages,

        @Schema(description = "기록에서 확인된 실패 단계 및 run을 중단한 필수 단계의 생략. partial 기록에서는 전체 실패 목록이 아닐 수 있다.")
        @JsonProperty("failed_stages")
        List<String> failedStages,

        @Schema(
                description =
                        "조회 대상 run에서 실패/오류 생략으로 누락된 채널. asr은 음성 보완 누락이며 제공 자막 전체의 부재를 뜻하지 않는다. 채널 단계 중 미확인 상태가 있으면 null.",
                nullable = true)
        @JsonProperty("missing_channels")
        List<String> missingChannels,

        @Schema(
                description = "사용자 수동 재처리 가능 여부. 현재 병합된 실행기는 수동 판정을 저장하지 않으므로 null. 워커 retryable이나 자동 재시도와 무관하다.",
                nullable = true)
        Boolean retryable,

        TranscriptResponse transcript) {
    public static ProcessingDetailsResponse from(ProcessingDetailsResult value) {
        if (value == null) return null;
        return new ProcessingDetailsResponse(
                Long.toString(value.pipelineRunId()),
                value.recordStatus(),
                value.stages().stream().map(StageResponse::from).toList(),
                value.failedStages(),
                value.missingChannels(),
                null,
                TranscriptResponse.from(value.transcript()));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StageResponse(
            String name,

            @Schema(allowableValues = {"pending", "running", "succeeded", "failed", "skipped", "unknown"})
            String status,

            Integer attempts,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("finished_at") Instant finishedAt,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("reason_code") String reasonCode,

            @Schema(
                    description =
                            "BE가 승인한 다음 자동 시도가 현재 대기 중이면 true. 배정 후에는 false이며 향후 실패의 재시도 가능성을 예측하지 않는다. 워커 신고와 무관하고 구 기록의 미확인은 null.",
                    nullable = true)
            @JsonProperty("automatic_retryable")
            Boolean automaticRetryable,

            @Schema(description = "최초 시도를 포함해 저장된 단계별 예산. 현재 프로파일로 다시 계산하지 않으며 구 기록에 없으면 null.", nullable = true)
            @JsonProperty("max_attempts")
            Integer maxAttempts,

            @Schema(
                    description = "재시도로 넘어간 이전 실패 기록. 최종 실패는 현재 단계의 status/error_code에 남는다. 기록 부재는 null.",
                    nullable = true)
            @JsonProperty("failed_attempts")
            List<FailedAttemptResponse> failedAttempts) {
        static StageResponse from(ProcessingDetailsResult.Stage value) {
            return new StageResponse(
                    value.name(),
                    value.status(),
                    value.attempts(),
                    value.startedAt(),
                    value.finishedAt(),
                    value.errorCode(),
                    value.reasonCode(),
                    value.automaticRetryable(),
                    value.maxAttempts(),
                    value.failedAttempts() == null
                            ? null
                            : value.failedAttempts().stream()
                                    .map(FailedAttemptResponse::from)
                                    .toList());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FailedAttemptResponse(
            Integer attempt,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("finished_at") Instant finishedAt) {
        static FailedAttemptResponse from(ProcessingDetailsResult.FailedAttempt value) {
            return new FailedAttemptResponse(value.attempt(), value.errorCode(), value.finishedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TranscriptResponse(
            @JsonProperty("record_status") String recordStatus,

            @Schema(
                    description =
                            "출처를 읽은 채택 snapshot 단계. transcript_selection은 중간 선택, scene_transcript_mapping은 최종 선택이다.")
            @JsonProperty("selection_stage")
            String selectionStage,

            @Schema(
                    description =
                            "저장된 decisions에서 selected=true인 출처만 포함한다(uploaded/embedded/asr). ASR 원문 후보나 보관 전용 제외 원문은 포함하지 않는다. 미확인은 null, 확인된 빈 선택은 [].")
            @JsonProperty("used_sources")
            List<String> usedSources,

            @Schema(description = "실제 채택 결정에 기록된 사유만 포함한다. PREFERRED_SUBTITLE/ASR_SUPPLEMENT.")
            @JsonProperty("adoption_reasons")
            List<String> adoptionReasons,

            @JsonProperty("representative_source") String representativeSource,
            @JsonProperty("asr_required") Boolean asrRequired,
            @JsonProperty("selection_reason") String selectionReason,
            @JsonProperty("asr_status") String asrStatus,

            @Schema(description = "저장된 사유만 반환. NO_SPEECH_DETECTED는 발화 미감지이며 확정 무음이 아니다. 빈 segments만으로 사유를 만들지 않는다.")
            @JsonProperty("asr_reason")
            String asrReason,

            @JsonProperty("asr_segment_count") Integer asrSegmentCount,
            @JsonProperty("embedded_status") String embeddedStatus) {
        static TranscriptResponse from(ProcessingDetailsResult.Transcript value) {
            if (value == null) return null;
            return new TranscriptResponse(
                    value.recordStatus(),
                    value.selectionStage(),
                    value.usedSources(),
                    value.adoptionReasons(),
                    value.representativeSource(),
                    value.asrRequired(),
                    value.selectionReason(),
                    value.asrStatus(),
                    value.asrReason(),
                    value.asrSegmentCount(),
                    value.embeddedStatus());
        }
    }
}
