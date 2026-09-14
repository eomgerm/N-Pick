package com.npick.tag.domain.repository;

import com.npick.tag.domain.model.ReviewerTagJudgment;

/**
 * 검수자 태그 교정 후보를 쓴다(S15P21A501-160). 우선순위 해석(읽기, S15P21A501-161)과 책임이 다르므로 별도 포트다.
 *
 * <p>모든 판단은 {@code confirmed=false} 로 저장되어 확정(-84) 전까지 검색·해석에 반영되지 않는다.
 */
public interface TagCorrectionCandidateRepository {

    /** 태그 판단 하나를 후보로 저장하고 생성된 {@code evidence_id} 를 준다. 대상 태그·태깅이 없으면 만든다. */
    long addJudgment(ReviewerTagJudgment judgment);
}
