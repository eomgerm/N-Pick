package com.npick.search.presentation.response;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.search.application.query.SearchHistoryRecord;

/**
 * 「내 검색 기록」상세 응답 (S15P21A501-198). 목록 항목의 모든 필드에 {@code search_snapshot} 을 더한다.
 *
 * <p>{@code search_snapshot} 은 {@code POST /search} 성공 {@code data} 와 <b>같은</b> object 이며 안에 envelope 를
 * 중첩하지 않는다. 당시 기록({@code explain_json}·{@code filtered_json})에서 복원하고 현재 태그·resolver·검색 API 로
 * 재계산하지 않는다(FRD §7.2). 복원 불가면 null 이고 {@code snapshot_status} 가 그 사실을 말한다.
 */
public record SearchHistoryDetailResponse(
        @JsonProperty("search_execution_id") String searchExecutionId,
        @JsonProperty("query_text") String queryText,
        @JsonProperty("explicit_filters") JsonNode explicitFilters,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("status") String status,
        @JsonProperty("snapshot_status") String snapshotStatus,
        @JsonProperty("result_count") Integer resultCount,
        @JsonProperty("representative_result") JsonNode representativeResult,
        @JsonProperty("search_snapshot") JsonNode searchSnapshot) {

    public static SearchHistoryDetailResponse from(SearchHistoryRecord record) {
        SearchSnapshot snapshot = SearchSnapshot.from(record);
        return new SearchHistoryDetailResponse(
                String.valueOf(record.item().searchExecutionId()),
                record.item().queryText(),
                snapshot.explicitFilters(),
                record.item().createdAt(),
                record.item().status(),
                snapshot.snapshotStatus(),
                snapshot.resultCount(),
                snapshot.representativeResult(),
                snapshot.payload());
    }
}
