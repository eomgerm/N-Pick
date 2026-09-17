package com.npick.search.presentation.response;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.search.application.query.SearchHistoryListPage;
import com.npick.search.application.query.SearchHistoryRecord;

/**
 * 「내 검색 기록」목록 응답 (S15P21A501-198).
 *
 * <p>신규 계약이라 필드는 snake_case, 모든 {@code *_id} 는 십진 문자열이다(FE 가 Number 로 바꾸지 않도록). nullable
 * ({@code result_count}·{@code representative_result})은 key 를 생략하지 않고 null 로 명시한다. 기존 camelCase 응답은
 * 건드리지 않으므로 전역 전략 대신 필드별 {@link JsonProperty} 로 고정한다({@code MyInquiryListResponse} 와 같은 방식).
 *
 * <p>스냅샷 판정은 {@link SearchSnapshot} 한 곳에서만 한다 — 상세와 같은 규칙을 쓰기 위함이다.
 */
public record SearchHistoryListResponse(
        @JsonProperty("items") List<Item> items,
        @JsonProperty("page") int page,
        @JsonProperty("size") int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") long totalPages,
        @JsonProperty("has_next") boolean hasNext) {

    /** 목록 항목. 상세 전용인 {@code search_snapshot} 은 싣지 않는다. */
    public record Item(
            @JsonProperty("search_execution_id") String searchExecutionId,
            @JsonProperty("query_text") String queryText,
            @JsonProperty("explicit_filters") JsonNode explicitFilters,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("status") String status,
            @JsonProperty("snapshot_status") String snapshotStatus,
            @JsonProperty("result_count") Integer resultCount,
            @JsonProperty("representative_result") JsonNode representativeResult) {}

    public static SearchHistoryListResponse of(SearchHistoryListPage page, int pageNumber, int size) {
        long totalPages = size == 0 ? 0 : (page.totalElements() + size - 1) / size;
        boolean hasNext = pageNumber + 1L < totalPages;
        List<Item> items =
                page.records().stream().map(SearchHistoryListResponse::toItem).toList();
        return new SearchHistoryListResponse(items, pageNumber, size, page.totalElements(), totalPages, hasNext);
    }

    static Item toItem(SearchHistoryRecord record) {
        SearchSnapshot snapshot = SearchSnapshot.from(record);
        return new Item(
                String.valueOf(record.item().searchExecutionId()),
                record.item().queryText(),
                SearchSnapshot.explicitFilters(record.item().explicitFiltersJson()),
                record.item().createdAt(),
                record.item().status(),
                snapshot.snapshotStatus(),
                snapshot.resultCount(),
                snapshot.representativeResult());
    }
}
