package com.npick.feedback.presentation.response;

import java.util.List;

import com.npick.feedback.application.query.InquiryListPage;
import com.npick.feedback.application.query.StatusCounts;

public record InquiryListResponse(
        List<InquiryListItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        StatusCountsResponse statusCounts) {

    public static InquiryListResponse of(InquiryListPage pageData, int page, int size) {
        int totalPages = size > 0 ? (int) Math.ceil((double) pageData.totalElements() / size) : 0;
        return new InquiryListResponse(
                pageData.items().stream().map(InquiryListItemResponse::from).toList(),
                page,
                size,
                pageData.totalElements(),
                totalPages,
                StatusCountsResponse.from(pageData.statusCounts()));
    }

    public record StatusCountsResponse(long open, long reviewing, long closed) {
        public static StatusCountsResponse from(StatusCounts counts) {
            return new StatusCountsResponse(counts.open(), counts.reviewing(), counts.closed());
        }
    }
}
