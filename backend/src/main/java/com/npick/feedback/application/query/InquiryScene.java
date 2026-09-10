package com.npick.feedback.application.query;

public record InquiryScene(
        long sceneId,
        long clipId,
        String clipTitle,
        long startTimeMs,
        long endTimeMs,
        long pipelineRunId,
        int processingNo) {}
