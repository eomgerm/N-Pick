package com.npick.feedback.presentation.response;

import com.npick.feedback.domain.model.Feedback;

public record InquiryResponse(long feedbackId, String status) {
    public static InquiryResponse from(Feedback f) {
        return new InquiryResponse(f.feedbackId(), f.status().name());
    }
}
