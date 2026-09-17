package com.npick.feedback.application;

import com.npick.feedback.application.query.InquiryListPage;

public interface ListInquiriesUseCase {
    InquiryListPage list(String statusFilter, int page, int size);
}
