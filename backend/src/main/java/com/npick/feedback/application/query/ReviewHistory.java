package com.npick.feedback.application.query;

import java.time.Instant;

public record ReviewHistory(
        Long reviewedById,
        String reviewerName,
        String reviewerLoginId,
        Instant reviewStartedAt,
        Long verifiedByExecutionId) {}
