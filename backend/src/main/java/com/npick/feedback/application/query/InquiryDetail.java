package com.npick.feedback.application.query;

import java.time.Instant;
import java.util.List;

public record InquiryDetail(
        long feedbackId,
        String status,
        String resolution,
        String resolutionNote,
        Instant createdAt,
        String comment,
        String resultExplainJson,
        ExecutionSnapshot execution,
        List<SceneEvidence> evidence,
        ReviewHistory history) {}
