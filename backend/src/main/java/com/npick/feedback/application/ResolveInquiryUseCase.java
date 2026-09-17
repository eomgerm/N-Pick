package com.npick.feedback.application;

public interface ResolveInquiryUseCase {
    void resolve(long feedbackId, long reviewerId, String rawResolution, String note);
}
