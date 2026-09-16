package com.npick.search.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.npick.search.infrastructure.persistence.entity.SearchRuleJpaEntity;

/**
 * 해석 교정 규칙 확정 쓰기(S15P21A501-84). {@code active} 한 칸만 바꾸므로 native {@code UPDATE} 로 쓴다.
 *
 * <p>활성화는 {@code source_feedback_id} 로 좁혀 이 신고의 후보만 켜고, {@code active} 값 조건으로 이미 그 상태인 행을 건드리지 않는다(멱등). 비활성화(교체 대상)는 이
 * 신고 소속이 아닐 수 있으므로 id 로만 좁힌다.
 */
public interface SearchRuleConfirmationJpaRepository extends JpaRepository<SearchRuleJpaEntity, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE search_rule SET active = true, updated_at = now() "
                    + "WHERE search_rule_id = :ruleId AND source_feedback_id = :feedbackId AND active = false",
            nativeQuery = true)
    int activate(@Param("feedbackId") long feedbackId, @Param("ruleId") long ruleId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE search_rule SET active = false, updated_at = now() "
                    + "WHERE search_rule_id = :ruleId AND active = true",
            nativeQuery = true)
    int deactivate(@Param("ruleId") long ruleId);
}
