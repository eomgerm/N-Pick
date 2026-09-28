package com.npick.feedback.presentation.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.npick.common.response.StoredExplainKeywords;
import com.npick.feedback.application.query.MyInquiryDetail;

/**
 * 「내 문의 기록」 상세 응답 (S15P21A501-185). 목록 응답과 같은 snake_case/문자열 ID 규칙을 따른다({@link MyInquiryListResponse}).
 *
 * <p>{@code snapshot_status}/{@code result_snapshot} 판정: 저장된 {@code result_explain_json}의 {@code display.display_name}
 * (-60 생산자 형식 {@code {"display":{"display_name":...}}})이 문자열(빈 문자열 포함)이거나 {@code null}이면 available과 당시 순위·explain을 담은
 * 스냅샷을 반환한다. 생산자(-59)는 nullable {@code clip.title}을 그대로 기록하므로 {@code null}은 "제목 없는 영상"이라는 유효한 과거 값이며 그대로 보존한다(대체 표기는 표현
 * 계층이 정한다). {@code display} 블록·키가 없거나 문자열·null이 아니면(숫자·객체 등 미기록·불완전) unavailable과 null을 반환한다. {@link #from}이 이 판정을 모으는
 * 유일한 지점이다. 표시값은 저장된 explain_json에서만 오며 현재 태그/장면으로 재생성하지 않는다.
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
        // 재생성 금지: display 값은 저장된 explain 블롭에서만 온다. -60 생산자 형식은 표시값을
        // {"display":{"display_name":...}} 로 중첩 저장한다(SearchExecutionRecordingDbTest, /display/display_name).
        // 생산자(-59)는 display_name 에 nullable clip.title(SearchExplain#display)을 그대로 기록하므로 null 은
        // "제목 없는 영상"이라는 유효한 과거 값이다 — available 로 보존하고 대체 문구는 표현 계층(FE)이 정한다.
        // 정상 스냅샷 판정: display.display_name 이 문자열(빈 문자열 포함)이거나 null. display 블록/키 부재·숫자·객체는
        // 미기록·불완전 → unavailable. 현재 태그/장면으로 채우지 않는다.
        JsonNode displayName = explain.at("/display/display_name");
        if (!displayName.isTextual() && !displayName.isNull()) {
            return null;
        }
        // 출처를 남기지 않던 시절의 matched_keywords 를 내 검색 기록과 같은 규칙으로 맞춘다 (S15P21A501-234).
        // 두 복원 화면이 다른 변환을 타면 같은 기록이 화면마다 다른 출처로 보인다.
        if (explain.get("match") instanceof ObjectNode match) {
            StoredExplainKeywords.normalize(match);
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
