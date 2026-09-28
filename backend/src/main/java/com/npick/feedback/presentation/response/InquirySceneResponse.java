package com.npick.feedback.presentation.response;

import com.npick.feedback.application.query.InquiryScene;

public record InquirySceneResponse(
        long sceneId,
        long clipId,
        String clipTitle,
        long startTimeMs,
        long endTimeMs,
        long pipelineRunId,
        int processingNo) {

    public static InquirySceneResponse from(InquiryScene scene) {
        return new InquirySceneResponse(
                scene.sceneId(),
                scene.clipId(),
                scene.clipTitle(),
                scene.startTimeMs(),
                scene.endTimeMs(),
                scene.pipelineRunId(),
                scene.processingNo());
    }
}
