package com.npick.feedback.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.feedback.application.query.InquiryDetail;
import com.npick.feedback.application.query.InquiryDetailQuery;
import com.npick.feedback.application.query.InquiryListItem;
import com.npick.feedback.application.query.InquiryListQuery;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;
import com.npick.feedback.domain.repository.FeedbackRepository;

@Service
public class InquiryReviewService {

    private final InquiryListQuery listQuery;
    private final InquiryDetailQuery detailQuery;
    private final FeedbackRepository repository;

    public InquiryReviewService(
            InquiryListQuery listQuery, InquiryDetailQuery detailQuery, FeedbackRepository repository) {
        this.listQuery = listQuery;
        this.detailQuery = detailQuery;
        this.repository = repository;
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

    @Transactional
    public void claim(long feedbackId, long reviewerId) {
        repository.findById(feedbackId).orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
        if (repository.claim(feedbackId, reviewerId, Instant.now()) == 0) {
            throw new FeedbackException(FeedbackErrorCode.ALREADY_CLAIMED);
        }
    }
}
