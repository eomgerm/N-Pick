package com.npick.search.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.search.application.error.SearchRuleDeactivationErrorCode;
import com.npick.search.domain.repository.SearchRuleDeactivationRepository;

/**
 * 승인된 교정 규칙을 사용 중단한다 (S15P21A501-86, F-11).
 *
 * <p>끄기만 한다. 재사용(재활성화)은 여기서 켜지 않고 현재 조건에서 검증·승인하는 경로(-83→-84)를 지나므로, 목표 상태가 {@code active=true} 인 요청은 거부한다. 사유는 받아 검증하되
 * 저장하지 않는다 — FRD 가 켜기·끄기의 수행자·사유·시각 전체 복원을 명시적으로 de-scope 한다.
 */
@Service
public class DeactivateSearchRuleService {

    private final SearchRuleDeactivationRepository repository;
    private final CorrectionStateLock correctionStateLock;

    public DeactivateSearchRuleService(
            SearchRuleDeactivationRepository repository, CorrectionStateLock correctionStateLock) {
        this.repository = repository;
        this.correctionStateLock = correctionStateLock;
    }

    @Transactional
    public void deactivate(DeactivateSearchRuleCommand command) {
        if (!command.reviewerRole()) {
            throw new BusinessException(SearchRuleDeactivationErrorCode.EDITOR_FORBIDDEN);
        }
        if (command.active()) {
            throw new BusinessException(SearchRuleDeactivationErrorCode.CANNOT_REACTIVATE);
        }
        // 규칙 중단은 확정 지문에 든 활성 규칙 집합을 바꾸므로, 확정과 같은 잠금을 잡아 확정의 drift 검사를 우회하지 못하게 한다(F-13, S15P21A501-84 리뷰).
        correctionStateLock.acquire();
        if (repository.deactivate(command.ruleId()) == 0) {
            // 0 행 — 없는 규칙인지 이미 꺼진 규칙인지 갈라 원인을 알린다.
            throw new BusinessException(
                    repository.exists(command.ruleId())
                            ? SearchRuleDeactivationErrorCode.ALREADY_INACTIVE
                            : SearchRuleDeactivationErrorCode.RULE_NOT_FOUND);
        }
    }
}
