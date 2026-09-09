package com.npick.feedback.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.feedback.application.query.InquiryDetail;
import com.npick.feedback.application.query.InquiryDetailQuery;
import com.npick.feedback.application.query.InquiryListItem;
import com.npick.feedback.application.query.InquiryListPage;
import com.npick.feedback.application.query.InquiryListQuery;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;
import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackStatus;
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

    @Transactional(readOnly = true)
    public InquiryListPage list(String statusFilter, int page, int size) {
        String status = (statusFilter == null || statusFilter.isBlank()) ? null : statusFilter.toUpperCase(Locale.ROOT);
        List<InquiryListItem> items = listQuery.findByStatus(status, page, size);
        long totalElements = listQuery.countByStatus(status);
        return new InquiryListPage(items, totalElements, listQuery.countGroupedByStatus());
    }

    public InquiryDetail detail(long feedbackId) {
        return detailQuery
                .findById(feedbackId)
                .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
    }

    @Transactional
    public void claim(long feedbackId, long reviewerId) {
        if (repository.claim(feedbackId, reviewerId, Instant.now()) == 0) {
            // CAS 0행. 최신 상태를 CAS 이후에 다시 읽어 판정한다(경합·재시도·종료 반영).
            // 존재하지 않으면 404, 지금도 이 검수자가 잡고 있는 reviewing이면 재시도로 보고 성공(소유자 멱등), 그 외엔 충돌.
            Feedback current = repository
                    .findById(feedbackId)
                    .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
            boolean ownedByCaller = current.status() == FeedbackStatus.REVIEWING
                    && current.reviewedById() != null
                    && current.reviewedById() == reviewerId;
            if (!ownedByCaller) {
                throw new FeedbackException(FeedbackErrorCode.ALREADY_CLAIMED);
            }
        }
    }
}
