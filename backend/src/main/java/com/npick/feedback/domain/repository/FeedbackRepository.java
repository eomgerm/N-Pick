package com.npick.feedback.domain.repository;

import java.time.Instant;
import java.util.Optional;

import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackResolution;

public interface FeedbackRepository {
    Feedback save(Feedback feedback);

    Optional<Feedback> findById(long feedbackId);

    Optional<Feedback> findByResultAndCreator(long searchResultId, long createdById);

    /**
     * 검색 결과가 존재하고, 그 검색을 {@code searchedById} 본인이 실행했는지 확인한다. 타인이 실행한 검색 결과에 문의를 달면 조회 시 그 검색어·필터가 노출되므로(S15P21A501-185
     * 리뷰), 생성 단계에서 막는다. 존재 여부를 노출하지 않으려 타인 검색과 미존재를 구분하지 않는다.
     */
    boolean existsSearchResultSearchedBy(long searchResultId, long searchedById);

    int claim(long feedbackId, long reviewerId, Instant reviewStartedAt);

    int editComment(long feedbackId, long ownerId, String comment);

    /**
     * reviewing 상태이고 담당 검수자 본인일 때만 처리 결과를 기록한다(CAS). status·closed_at 파생은 어댑터가 {@link FeedbackResolution}에서 결정하므로 호출자가
     * 잘못된 상태 문자열을 넘길 수 없다. note 를 생략하면 기존 사유를 유지한다.
     *
     * @return 갱신된 행 수. 0 이면 이미 종료됐거나 담당이 아니어서 진 것이다.
     */
    int resolve(long feedbackId, long reviewerId, FeedbackResolution resolution, String note, Instant now);

    /**
     * reviewing 상태이고 담당 검수자 본인일 때만 교정을 확정한다(CAS, S15P21A501-84, F-13). 검증 실행을 연결하고({@code verified_by_execution_id}),
     * 최종 승인한 교정 규칙을 {@code created_rule_id} 에 기록하며(태그만 교정하면 {@code null}), 신고를 closed 로 종료한다. resolution 은 그대로 둔다.
     *
     * @return 갱신된 행 수. 0 이면 이미 종료됐거나 담당이 아니거나 판정이 바뀌어 진 것이다.
     */
    int confirm(
            long feedbackId,
            long reviewerId,
            long executionId,
            Long createdRuleId,
            String expectedResolution,
            Instant now);
}
