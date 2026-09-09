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
import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackResolution;
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

    /**
     * 검수 처리 결과를 기록한다(F-09). 교정 3종은 reviewing 유지, no_action·deferred 는 사유를 필수로 받아 이 자리에서 closed 로 종료한다. reviewing 동안은
     * 판정을 몇 번이든 덮어쓸 수 있고, closed 후엔 CAS 가드로 잠긴다. 교정 후보 생성·검증은 이 API 범위가 아니다.
     */
    @Transactional
    public void resolve(long feedbackId, long reviewerId, String rawResolution, String note) {
        FeedbackResolution resolution = FeedbackResolution.parse(rawResolution);
        if (resolution == null) {
            throw new FeedbackException(FeedbackErrorCode.INVALID_RESOLUTION);
        }
        // 공백만 있는 사유는 "미제공"으로 정규화한다. 그래야 COALESCE 가 기존 사유를 지우지 않고 유지한다.
        String normalizedNote = (note == null || note.isBlank()) ? null : note;
        if (resolution.isNoteRequired() && normalizedNote == null) {
            throw new FeedbackException(FeedbackErrorCode.NOTE_REQUIRED);
        }
        Feedback feedback = repository
                .findById(feedbackId)
                .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
        if (feedback.status() != FeedbackStatus.REVIEWING) {
            throw new FeedbackException(FeedbackErrorCode.NOT_RESOLVABLE);
        }
        if (feedback.reviewedById() == null || feedback.reviewedById() != reviewerId) {
            throw new FeedbackException(FeedbackErrorCode.NOT_REVIEWER);
        }
        if (repository.resolve(feedbackId, reviewerId, resolution, normalizedNote, Instant.now()) == 0) {
            throw new FeedbackException(FeedbackErrorCode.NOT_RESOLVABLE); // 조회~갱신 사이 경합
        }
    }
}
