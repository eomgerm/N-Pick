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

    /**
     * reviewing 상태이고 담당 검수자 본인일 때만 처리 결과를 기록한다(CAS). 종료성 판정이면 status='CLOSED'·closed_at 을 함께 쓴다.
     *
     * @return 갱신된 행 수. 0 이면 이미 종료됐거나 담당이 아니어서 진 것이다.
     */
    int resolve(
            long feedbackId,
            long reviewerId,
            String resolution,
            String note,
            String newStatus,
            Instant closedAt,
            Instant now);
}
