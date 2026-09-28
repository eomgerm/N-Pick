package com.npick.search.infrastructure.persistence.repository;

import org.springframework.stereotype.Repository;

import com.npick.search.domain.repository.SearchRuleConfirmationRepository;

/** 해석 교정 규칙 확정 쓰기 어댑터(S15P21A501-84). 후보 활성화·교체 대상 비활성화를 native {@code UPDATE} 로 확정한다. */
@Repository
public class SearchRuleConfirmationRepositoryAdapter implements SearchRuleConfirmationRepository {

    private final SearchRuleConfirmationJpaRepository jpaRepository;

    public SearchRuleConfirmationRepositoryAdapter(SearchRuleConfirmationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public int activate(long sourceFeedbackId, long ruleId) {
        return jpaRepository.activate(sourceFeedbackId, ruleId);
    }

    @Override
    public int deactivate(long ruleId) {
        return jpaRepository.deactivate(ruleId);
    }

    @Override
    public int discardPending(long sourceFeedbackId) {
        return jpaRepository.discardPending(sourceFeedbackId);
    }

    @Override
    public int discardPendingParse(long sourceFeedbackId) {
        return jpaRepository.discardPendingParse(sourceFeedbackId);
    }
}
