package com.npick.feedback.domain.repository;

import java.time.Instant;
import java.util.Optional;

import com.npick.feedback.domain.model.Feedback;

public interface FeedbackRepository {
    Feedback save(Feedback feedback);

    Optional<Feedback> findById(long feedbackId);

    Optional<Feedback> findByResultAndCreator(long searchResultId, long createdById);

    boolean existsSearchResult(long searchResultId);

    int claim(long feedbackId, long reviewerId, Instant reviewStartedAt);

    int editComment(long feedbackId, long ownerId, String comment);
}
