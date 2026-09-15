package com.npick.search.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.npick.search.infrastructure.persistence.entity.SearchRuleJpaEntity;

/** 규칙 사용 중단 쓰기(S15P21A501-86). {@code active} 한 칸만 끄므로 native {@code UPDATE} 로 쓴다. */
public interface SearchRuleDeactivationJpaRepository extends JpaRepository<SearchRuleJpaEntity, Long> {

    // active=true 조건이 곧 기대 상태 비교(CAS)다 — 동시 요청·재클릭이 두 번 끄지 않고, 이미 꺼진 규칙은 0 행이 온다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE search_rule SET active = false, updated_at = now() "
                    + "WHERE search_rule_id = :ruleId AND active = true",
            nativeQuery = true)
    int deactivate(@Param("ruleId") long searchRuleId);

    @Query(value = "SELECT count(*) > 0 FROM search_rule WHERE search_rule_id = :ruleId", nativeQuery = true)
    boolean existsRule(@Param("ruleId") long searchRuleId);
}
