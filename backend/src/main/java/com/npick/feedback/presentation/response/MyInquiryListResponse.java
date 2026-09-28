package com.npick.feedback.presentation.response;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.feedback.application.query.MyInquiryListItem;
import com.npick.feedback.application.query.MyInquiryListPage;

/**
 * 「내 문의 기록」 목록 응답 (S15P21A501-185).
 *
 * <p>신규 계약이라 필드는 snake_case, 모든 *_id 는 십진 문자열이다(FE 가 Number 변환하지 않도록). nullable(comment·resolution)은 key 를 생략하지 않고 null
 * 로 명시한다. 기존 검수/문의 API 의 camelCase 응답은 건드리지 않으므로 전역 전략 대신 필드별 {@link JsonProperty} 로 snake_case 를 고정한다.
 */
public record MyInquiryListResponse(
        @JsonProperty("items") List<Item> items,
        @JsonProperty("page") int page,
        @JsonProperty("size") int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") long totalPages,
        @JsonProperty("has_next") boolean hasNext) {

    public record Item(
            @JsonProperty("feedback_id") String feedbackId,
            @JsonProperty("search_execution_id") String searchExecutionId,
            @JsonProperty("search_result_id") String searchResultId,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("query_text") String queryText,
            @JsonProperty("comment") String comment,
            @JsonProperty("status") String status,
            @JsonProperty("resolution") String resolution,
            @JsonProperty("scene") Scene scene) {}

    public record Scene(
            @JsonProperty("scene_id") String sceneId,
            @JsonProperty("clip_id") String clipId,
            @JsonProperty("clip_title") String clipTitle,
            @JsonProperty("start_time_ms") long startTimeMs,
            @JsonProperty("end_time_ms") long endTimeMs) {}

    public static MyInquiryListResponse of(MyInquiryListPage page, int pageNumber, int size) {
        long totalPages = size == 0 ? 0 : (page.totalElements() + size - 1) / size;
        boolean hasNext = pageNumber + 1L < totalPages;
        List<Item> items =
                page.items().stream().map(MyInquiryListResponse::toItem).toList();
        return new MyInquiryListResponse(items, pageNumber, size, page.totalElements(), totalPages, hasNext);
    }

    private static Item toItem(MyInquiryListItem item) {
        Scene scene = new Scene(
                String.valueOf(item.scene().sceneId()),
                String.valueOf(item.scene().clipId()),
                item.scene().clipTitle(),
                item.scene().startTimeMs(),
                item.scene().endTimeMs());
        return new Item(
                String.valueOf(item.feedbackId()),
                String.valueOf(item.searchExecutionId()),
                String.valueOf(item.searchResultId()),
                item.createdAt(),
                item.updatedAt(),
                item.queryText(),
                item.comment(),
                item.status(),
                item.resolution(),
                scene);
    }
}
