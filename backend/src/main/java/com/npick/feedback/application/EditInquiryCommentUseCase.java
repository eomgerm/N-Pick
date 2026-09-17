package com.npick.feedback.application;

public interface EditInquiryCommentUseCase {
    void editComment(long feedbackId, long ownerId, String comment);
}
