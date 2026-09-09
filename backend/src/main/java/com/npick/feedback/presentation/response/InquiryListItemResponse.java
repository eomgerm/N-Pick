package com.npick.feedback.presentation.response;

import java.time.Instant;

import com.npick.feedback.application.query.InquiryListItem;

public record InquiryListItemResponse(
        long feedbackId,
        String status,
        String resolution,
        Instant createdAt,
        String queryText,
        long sceneId,
        boolean hasComment) {
    public static InquiryListItemResponse from(InquiryListItem item) {
        return new InquiryListItemResponse(
                item.feedbackId(),
                item.status(),
                item.resolution(),
                item.createdAt(),
                item.queryText(),
                item.sceneId(),
                item.hasComment());
    }
}
