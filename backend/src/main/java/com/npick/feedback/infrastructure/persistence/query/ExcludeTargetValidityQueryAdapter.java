package com.npick.feedback.infrastructure.persistence.query;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.npick.feedback.application.port.ExcludeTargetValidityPort;

/**
 * 제외 규칙의 대상 장면이 현재 제공 중인 처리에 속하는지 읽는다 (S15P21A501-85, F-14).
 *
 * <p>{@code active_pipeline_run_id} 일치가 세대 격리다 — 재처리로 새 처리가 서면 그 처리의 새 장면만 이 조건을 통과하고, 구 장면(옛 {@code pipeline_run})은 걸러진다.
 * 논리 삭제된 클립도 제외한다. 태그 조회 어댑터와 같은 격리 규칙이다.
 */
@Repository
class ExcludeTargetValidityQueryAdapter implements ExcludeTargetValidityPort {

    private final EntityManager em;

    ExcludeTargetValidityQueryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public boolean targetSceneActive(long searchRuleId) {
        Object present = em.createNativeQuery("SELECT count(*) > 0 FROM npick.search_rule sr "
                        + "JOIN npick.scene s ON s.scene_id = sr.target_scene_id "
                        + "JOIN npick.clip c ON c.clip_id = s.clip_id "
                        + "    AND c.active_pipeline_run_id = s.pipeline_run_id AND c.deleted_at IS NULL "
                        + "WHERE sr.search_rule_id = :ruleId AND sr.action = 'exclude_scene'")
                .setParameter("ruleId", searchRuleId)
                .getSingleResult();
        return Boolean.TRUE.equals(present);
    }
}
