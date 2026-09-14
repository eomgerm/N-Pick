package com.npick.search.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.npick.search.infrastructure.persistence.entity.SearchRuleJpaEntity;

public interface SearchRuleJpaRepository extends JpaRepository<SearchRuleJpaEntity, Long> {

    /**
     * 활성 규칙을 action 으로 골라 {@code search_rule_id} 순으로 읽는다.
     *
     * <p>정렬을 쿼리에 두는 이유는 적용 순서가 재현 가능해야 하기 때문이다 (§7.2). Policy 도 한 번 더 정렬하므로 여기 정렬이 없어도 결과는 같지만, 조회 순서가 매번 달라지면 기록을 대조할
     * 때 무엇이 바뀐 것인지 가리기 어렵다.
     */
    List<SearchRuleJpaEntity> findByActionAndActiveIsTrueOrderBySearchRuleIdAsc(String action);
}
