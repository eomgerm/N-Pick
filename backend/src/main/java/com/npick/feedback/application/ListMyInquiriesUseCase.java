package com.npick.feedback.application;

import com.npick.feedback.application.query.MyInquiryListPage;

public interface ListMyInquiriesUseCase {
    MyInquiryListPage listMine(long ownerId, int page, int size);
}
