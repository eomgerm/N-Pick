package com.npick.feedback.application;

import com.npick.feedback.application.query.InquiryDetail;

public interface GetInquiryDetailUseCase {
    InquiryDetail detail(long feedbackId);
}
