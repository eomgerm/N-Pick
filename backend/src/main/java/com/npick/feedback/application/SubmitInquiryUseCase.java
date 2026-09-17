package com.npick.feedback.application;

import com.npick.feedback.domain.model.Feedback;

public interface SubmitInquiryUseCase {
    Feedback submit(long searchResultId, long createdById, String comment);
}
