package com.npick.feedback.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.npick.common.infrastructure.persistence.BaseJpaEntity;

@Entity
@Table(name = "feedback")
public class FeedbackJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "feedback_id")
    private Long feedbackId;

    @Column(name = "search_result_id", nullable = false)
    private Long searchResultId;

    @Column(name = "created_by_id", nullable = false)
    private Long createdById;

    @Column(name = "comment")
    private String comment;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "review_started_at", columnDefinition = "TIMESTAMPTZ(6)")
    private Instant reviewStartedAt;

    protected FeedbackJpaEntity() {}

    public FeedbackJpaEntity(
            Long feedbackId,
            Long searchResultId,
            Long createdById,
            String comment,
            String status,
            Long reviewedById,
            Instant reviewStartedAt) {
        this.feedbackId = feedbackId;
        this.searchResultId = searchResultId;
        this.createdById = createdById;
        this.comment = comment;
        this.status = status;
        this.reviewedById = reviewedById;
        this.reviewStartedAt = reviewStartedAt;
    }

    public Long getFeedbackId() {
        return feedbackId;
    }

    public Long getSearchResultId() {
        return searchResultId;
    }

    public Long getCreatedById() {
        return createdById;
    }

    public String getComment() {
        return comment;
    }

    public String getStatus() {
        return status;
    }

    public Long getReviewedById() {
        return reviewedById;
    }

    public Instant getReviewStartedAt() {
        return reviewStartedAt;
    }
}
