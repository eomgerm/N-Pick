package com.npick.feedback.application.query;

import java.time.Instant;
import java.util.List;

public record InquiryDetail(
        long feedbackId,
        String status,
        String resolution,
        Instant createdAt,
        String comment,
        ExecutionSnapshot execution,
        List<SceneEvidence> evidence,
        ReviewHistory history) {}
