package com.npick.feedback.presentation.response;

import java.time.Instant;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.feedback.application.query.MyInquiryDetail;

/**
 * 「내 문의 기록」 상세 응답 (S15P21A501-185). 목록 응답과 같은 snake_case/문자열 ID 규칙을 따른다({@link MyInquiryListResponse}).
 *
 * <p>{@code snapshot_status}/{@code result_snapshot} 판정: 저장된 {@code result_explain_json}에 {@code display_name}이
 * 있으면 available과 당시 순위·explain을 담은 스냅샷을, 없으면(빈 객체·score-only·null·공백) unavailable과 null을 반환한다.
 * {@link #from}이 이 판정을 모으는 유일한 지점이다. 표시값은 저장된 explain_json에서만 오며 현재 태그/장면으로 재생성하지 않는다.
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

    public record ResultSnapshot(
            @JsonProperty("search_result_id") String searchResultId,
            @JsonProperty("scene_id") String sceneId,
            int rank,
            JsonNode explain) {}

    public static MyInquiryDetailResponse from(MyInquiryDetail detail) {
        MyInquiryListResponse.Scene scene = new MyInquiryListResponse.Scene(
                String.valueOf(detail.scene().sceneId()),
                String.valueOf(detail.scene().clipId()),
                detail.scene().clipTitle(),
                detail.scene().startTimeMs(),
                detail.scene().endTimeMs());
        ResultSnapshot snapshot = restoreSnapshot(detail);
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
                snapshot == null ? "unavailable" : "available",
                snapshot);
    }

    private static ResultSnapshot restoreSnapshot(MyInquiryDetail detail) {
        String explainJson = detail.resultExplainJson();
        if (explainJson == null || explainJson.isBlank()) {
            return null;
        }
        JsonNode explain = parseExplain(explainJson);
        JsonNode displayName = explain.get("display_name");
        // 재생성 금지: display 값은 저장된 explain 블롭에서만 온다. display_name 이 없으면 -59 가 표시값 스냅샷을
        // 기록하지 않은 것(불완전) → unavailable. 현재 태그/장면으로 채우지 않는다.
        if (displayName == null || displayName.isNull()) {
            return null;
        }
        return new ResultSnapshot(
                String.valueOf(detail.searchResultId()),
                String.valueOf(detail.scene().sceneId()),
                detail.resultRank(),
                explain);
    }

    private static JsonNode parseExplain(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("result explain_json 파싱 실패", e);
        }
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
