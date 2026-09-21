package com.npick.feedback.application.query;

public record SceneEvidence(
        long taggingId,
        String tagType,
        String matchValue,
        String tagName,
        String source,
        String verifiedState,
        String scope) {}
