package com.npick.search.infrastructure.persistence.repository;

import org.springframework.stereotype.Repository;

import com.npick.search.domain.repository.SearchRuleDeactivationRepository;

/** 규칙 사용 중단 쓰기 어댑터(S15P21A501-86). */
@Repository
public class SearchRuleDeactivationRepositoryAdapter implements SearchRuleDeactivationRepository {

    private final SearchRuleDeactivationJpaRepository jpaRepository;

    public SearchRuleDeactivationRepositoryAdapter(SearchRuleDeactivationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public int deactivate(long searchRuleId) {
        return jpaRepository.deactivate(searchRuleId);
    }

    @Override
    public boolean exists(long searchRuleId) {
        return jpaRepository.existsRule(searchRuleId);
    }
}
