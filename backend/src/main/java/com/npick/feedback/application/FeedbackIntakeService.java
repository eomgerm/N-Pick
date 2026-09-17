package com.npick.feedback.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;
import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.repository.FeedbackRepository;

@Service
public class FeedbackIntakeService {

    private final FeedbackRepository repository;

    public FeedbackIntakeService(FeedbackRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Feedback submit(long searchResultId, long createdById, String comment) {
        // 검색 결과 존재 + 그 검색을 본인이 실행했을 때만 접수한다. 타인 검색에 문의를 달면 조회 시 그 검색어·필터가
        // 노출되므로(S15P21A501-185 리뷰) 생성 단계에서 막는다. 타인 검색·미존재는 구분 없이 동일 404.
        if (!repository.existsSearchResultSearchedBy(searchResultId, createdById)) {
            throw new FeedbackException(FeedbackErrorCode.RESULT_NOT_FOUND);
        }
        return repository.findByResultAndCreator(searchResultId, createdById).orElseGet(() -> {
            try {
                return repository.save(Feedback.open(searchResultId, createdById, comment));
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                return repository
                        .findByResultAndCreator(searchResultId, createdById)
                        .orElseThrow(() -> e);
            }
        });
    }

    @Transactional
    public void editComment(long feedbackId, long ownerId, String comment) {
        Feedback fb = repository
                .findById(feedbackId)
                .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
        if (!fb.isOwnedBy(ownerId)) {
            throw new FeedbackException(FeedbackErrorCode.NOT_OWNER);
        }
        if (!fb.isOpen()) {
            throw new FeedbackException(FeedbackErrorCode.NOT_EDITABLE);
        }
        if (repository.editComment(feedbackId, ownerId, comment) == 0) {
            throw new FeedbackException(FeedbackErrorCode.NOT_EDITABLE); // 조회~갱신 사이 경합
        }
    }
}
