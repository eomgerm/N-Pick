package com.npick.clip.presentation.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.clip.application.query.list.GetClipsResult;

public record ClipPageResponse(
        List<ClipSummaryResponse> items,
        int page,
        int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") long totalPages,
        @JsonProperty("has_next") boolean hasNext) {
    public static ClipPageResponse from(GetClipsResult result) {
        long pages = result.totalElements() / result.size() + (result.totalElements() % result.size() == 0 ? 0 : 1);
        return new ClipPageResponse(
                result.items().stream().map(ClipSummaryResponse::from).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                pages,
                (long) result.page() + 1 < pages);
    }
}
