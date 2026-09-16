package com.npick.feedback.application.port;

/**
 * 장면 제외 확정 직전에 대상 장면이 여전히 유효한지 확인한다 (S15P21A501-85, F-14).
 *
 * <p>재처리로 장면이 재추출되면 새 장면 ID 가 생기고 구 장면은 검색 대상이 아니다(현재 제공 중인 {@code clip.active_pipeline_run_id} 에 속하지 않는다). 확정이 그
 * 사라진 장면을 되살리지 않도록, 승격 직전에 다시 확인한다 — 검증 시점과 확정 시점 사이에 재처리가 끼어들 수 있기 때문이다.
 */
public interface ExcludeTargetValidityPort {

    /**
     * 제외 규칙의 대상 장면이 현재 제공 중인 처리에 속하는가.
     *
     * @return 유효하면 {@code true}. 규칙이 없거나 대상 장면이 사라졌으면 {@code false}
     */
    boolean targetSceneActive(long searchRuleId);
}
