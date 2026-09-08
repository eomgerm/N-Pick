package com.npick.feedback.application;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.npick.feedback.application.query.InquiryDetail;
import com.npick.feedback.application.query.InquiryDetailQuery;
import com.npick.feedback.application.query.InquiryListItem;
import com.npick.feedback.application.query.InquiryListQuery;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;

@Service
public class InquiryReviewService {

    private final InquiryListQuery listQuery;
    private final InquiryDetailQuery detailQuery;

    public InquiryReviewService(InquiryListQuery listQuery, InquiryDetailQuery detailQuery) {
        this.listQuery = listQuery;
        this.detailQuery = detailQuery;
    }

    public List<InquiryListItem> list(String statusFilter, int page, int size) {
        String status = (statusFilter == null || statusFilter.isBlank()) ? null : statusFilter.toUpperCase(Locale.ROOT);
        return listQuery.findByStatus(status, page, size);
    }

    public InquiryDetail detail(long feedbackId) {
        return detailQuery
                .findById(feedbackId)
                .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
    }
}
