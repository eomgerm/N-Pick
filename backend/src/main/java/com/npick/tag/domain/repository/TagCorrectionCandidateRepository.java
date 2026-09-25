package com.npick.tag.domain.repository;

import java.util.Optional;

import com.npick.tag.domain.model.ReviewerTagJudgment;

/**
 * 검수자 태그 교정 후보를 쓴다(S15P21A501-160). 우선순위 해석(읽기, S15P21A501-161)과 책임이 다르므로 별도 포트다.
 *
 * <p>모든 판단은 {@code confirmed=false} 로 저장되어 확정(-84) 전까지 검색·해석에 반영되지 않는다.
 */
public interface TagCorrectionCandidateRepository {

    /** 태그 판단 하나를 후보로 저장하고 생성된 {@code evidence_id} 를 준다. 대상 태그·태깅이 없으면 만든다. */
    long addJudgment(ReviewerTagJudgment judgment);

    /**
     * 같은 신고·같은 태깅(태그 유형·값·장면/클립 범위)·같은 판단으로 이미 대기 중인({@code confirmed=false}) 검수자 근거가 있으면 그 {@code evidence_id} 를 준다
     * (S15P21A501-317). 표시 이름은 비교하지 않는다. 확정된 근거는 재사용 대상이 아니다.
     */
    Optional<Long> findPendingJudgment(ReviewerTagJudgment judgment);

    /** 이 신고에서 만들어진 검수자 판단 수. 누적 개수 상한 판정에 쓴다 (S15P21A501-255). */
    int countByFeedback(long sourceFeedbackId);
}
