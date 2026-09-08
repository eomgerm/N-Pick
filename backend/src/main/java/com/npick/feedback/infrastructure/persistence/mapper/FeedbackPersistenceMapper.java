package com.npick.feedback.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackStatus;
import com.npick.feedback.infrastructure.persistence.entity.FeedbackJpaEntity;

@Component
public class FeedbackPersistenceMapper {

    public FeedbackJpaEntity toEntity(Feedback f) {
        return new FeedbackJpaEntity(
                f.feedbackId(),
                f.searchResultId(),
                f.createdById(),
                f.comment(),
                f.status().name(),
                f.reviewedById(),
                f.reviewStartedAt());
    }

    public Feedback toDomain(FeedbackJpaEntity e) {
        return new Feedback(
                e.getFeedbackId(),
                e.getSearchResultId(),
                e.getCreatedById(),
                e.getComment(),
                FeedbackStatus.valueOf(e.getStatus()),
                e.getReviewedById(),
                e.getReviewStartedAt());
    }
}
