package com.npick.feedback.application;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.npick.feedback.application.query.InquiryListItem;
import com.npick.feedback.application.query.InquiryListQuery;

@Service
public class InquiryReviewService {

    private final InquiryListQuery listQuery;

    public InquiryReviewService(InquiryListQuery listQuery) {
        this.listQuery = listQuery;
    }

    public List<InquiryListItem> list(String statusFilter, int page, int size) {
        String status = (statusFilter == null || statusFilter.isBlank()) ? null : statusFilter.toUpperCase(Locale.ROOT);
        return listQuery.findByStatus(status, page, size);
    }
}
