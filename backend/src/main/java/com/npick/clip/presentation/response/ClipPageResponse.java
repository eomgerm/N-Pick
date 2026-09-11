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
        @JsonProperty("has_next") boolean hasNext,

        @io.swagger.v3.oas.annotations.media.Schema(
                description = "논리 삭제를 제외한 전체 클립의 최신 run 상태별 건수. status 필터와 페이지에 독립적이며 no_run은 처리 기록 없는 클립.")
        @JsonProperty("run_counts")
        RunCountsResponse runCounts) {
    public static ClipPageResponse from(GetClipsResult result) {
        long pages = result.totalElements() / result.size() + (result.totalElements() % result.size() == 0 ? 0 : 1);
        return new ClipPageResponse(
                result.items().stream()
                        .map(item -> ClipSummaryResponse.from(
                                item,
                                item.latestRun() == null
                                        ? null
                                        : result.progress().get(item.latestRun().pipelineRunId())))
                        .toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                pages,
                (long) result.page() + 1 < pages,
                new RunCountsResponse(
                        result.runCounts().getOrDefault("queued", 0L),
                        result.runCounts().getOrDefault("running", 0L),
                        result.runCounts().getOrDefault("failed", 0L),
                        result.runCounts().getOrDefault("succeeded", 0L),
                        result.runCounts().getOrDefault("no_run", 0L)));
    }

    public record RunCountsResponse(
            long queued,
            long running,
            long failed,
            long succeeded,
            @JsonProperty("no_run") long noRun) {}
}
