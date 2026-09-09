package com.npick.feedback.application.query;

import java.util.List;

public interface InquiryListQuery {
    List<InquiryListItem> findByStatus(String statusOrNull, int page, int size);
}
