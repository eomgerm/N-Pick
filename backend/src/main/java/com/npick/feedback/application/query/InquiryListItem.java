package com.npick.feedback.application.query;

import java.time.Instant;

public record InquiryListItem(
        long feedbackId,
        String status,
        String resolution,
        Instant createdAt,
        String queryText,
        long sceneId,
        boolean hasComment) {}
