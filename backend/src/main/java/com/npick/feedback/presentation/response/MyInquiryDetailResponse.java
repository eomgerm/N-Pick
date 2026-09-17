package com.npick.feedback.presentation.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.npick.feedback.application.query.MyInquiryDetail;

/**
 * 「내 문의 기록」 상세 응답 (S15P21A501-185). 목록 응답과 같은 snake_case/문자열 ID 규칙을 따른다({@link MyInquiryListResponse}).
 *
 * <p>{@code snapshot_status}/{@code result_snapshot} 판정: 계약상 당시 검색결과 스냅샷(display_name·scene_description 등)을 담아야 하지만,
 * {@code search_result} 테이블에 이를 복원할 저장 컬럼이 아직 없다(선행 S15P21A501-60 이 저장 계약을 소유, 미착수). 이 메서드가 그 판정을 모으는 유일한 지점이다 — -60 이
 * 서면 여기서 available 경로를 채운다. 지금은 항상 unavailable/null 이다.
 */
public record MyInquiryDetailResponse(
        @JsonProperty("feedback_id") String feedbackId,
        @JsonProperty("search_execution_id") String searchExecutionId,
        @JsonProperty("search_result_id") String searchResultId,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("query_text") String queryText,
        @JsonProperty("comment") String comment,
        @JsonProperty("status") String status,
        @JsonProperty("resolution") String resolution,
        @JsonProperty("scene") MyInquiryListResponse.Scene scene,
        @JsonProperty("explicit_filters") JsonNode explicitFilters,
        @JsonProperty("resolution_note") String resolutionNote,
        @JsonProperty("review_started_at") Instant reviewStartedAt,
        @JsonProperty("closed_at") Instant closedAt,
        @JsonProperty("snapshot_status") String snapshotStatus,
        @JsonProperty("result_snapshot") Object resultSnapshot) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static MyInquiryDetailResponse from(MyInquiryDetail detail) {
        MyInquiryListResponse.Scene scene = new MyInquiryListResponse.Scene(
                String.valueOf(detail.scene().sceneId()),
                String.valueOf(detail.scene().clipId()),
                detail.scene().clipTitle(),
                detail.scene().startTimeMs(),
                detail.scene().endTimeMs());
        return new MyInquiryDetailResponse(
                String.valueOf(detail.feedbackId()),
                String.valueOf(detail.searchExecutionId()),
                String.valueOf(detail.searchResultId()),
                detail.createdAt(),
                detail.updatedAt(),
                detail.queryText(),
                detail.comment(),
                detail.status(),
                detail.resolution(),
                scene,
                parseExplicitFilters(detail.explicitFiltersJson()),
                detail.resolutionNote(),
                detail.reviewStartedAt(),
                detail.closedAt(),
                "unavailable",
                null);
    }

    private static JsonNode parseExplicitFilters(String json) {
        if (json == null || json.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("explicit_filters_json 파싱 실패", e);
        }
    }
}
