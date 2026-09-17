package com.npick.search.application;

/**
 * 승인된 교정 규칙을 사용 중단한다 (S15P21A501-86, F-11).
 */
public interface DeactivateSearchRuleUseCase {

    void deactivate(DeactivateSearchRuleCommand command);
}
