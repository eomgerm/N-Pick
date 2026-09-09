package com.npick.feedback.domain.repository;

import java.time.Instant;
import java.util.Optional;

import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackResolution;

public interface FeedbackRepository {
    Feedback save(Feedback feedback);

    Optional<Feedback> findById(long feedbackId);

    Optional<Feedback> findByResultAndCreator(long searchResultId, long createdById);

    boolean existsSearchResult(long searchResultId);

    int claim(long feedbackId, long reviewerId, Instant reviewStartedAt);

    int editComment(long feedbackId, long ownerId, String comment);

    /**
     * reviewing 상태이고 담당 검수자 본인일 때만 처리 결과를 기록한다(CAS). status·closed_at 파생은 어댑터가 {@link FeedbackResolution}에서 결정하므로 호출자가
     * 잘못된 상태 문자열을 넘길 수 없다. note 를 생략하면 기존 사유를 유지한다.
     *
     * @return 갱신된 행 수. 0 이면 이미 종료됐거나 담당이 아니어서 진 것이다.
     */
    int resolve(long feedbackId, long reviewerId, FeedbackResolution resolution, String note, Instant now);
}
