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
        if (!repository.existsSearchResult(searchResultId)) {
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
