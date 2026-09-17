package com.npick.feedback.application;

import com.npick.feedback.application.query.MyInquiryDetail;

public interface GetMyInquiryDetailUseCase {
    MyInquiryDetail detailMine(long feedbackId, long ownerId);
}
