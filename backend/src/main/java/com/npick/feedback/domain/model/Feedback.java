package com.npick.feedback.domain.model;

import java.time.Instant;

import com.npick.common.persistence.TsidGenerator;

public record Feedback(
        long feedbackId,
        long searchResultId,
        long createdById,
        String comment,
        FeedbackStatus status,
        Long reviewedById,
        Instant reviewStartedAt) {

    public static Feedback open(long searchResultId, long createdById, String comment) {
        return new Feedback(
                TsidGenerator.generate(), searchResultId, createdById, comment, FeedbackStatus.OPEN, null, null);
    }

    public boolean isOpen() {
        return status == FeedbackStatus.OPEN;
    }

    public boolean isOwnedBy(long memberId) {
        return createdById == memberId;
    }
}
