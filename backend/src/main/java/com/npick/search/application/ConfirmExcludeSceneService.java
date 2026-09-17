package com.npick.search.application;

import org.springframework.stereotype.Service;

import com.npick.search.domain.repository.SearchRuleConfirmationRepository;

/**
 * 장면 제외 규칙 확정 — search 도메인이 공개하는 UseCase (S15P21A501-85, F-13).
 *
 * <p>feedback 오케스트레이터가 확정 트랜잭션 안에서 호출한다. 제외는 교체가 없으므로 후보 규칙을 활성화하기만 한다(비활성화 대상 없음). 트랜잭션 경계는 호출부가 가진다.
 */
@Service
public class ConfirmExcludeSceneService implements ConfirmExcludeSceneUseCase {

    private final SearchRuleConfirmationRepository repository;

    public ConfirmExcludeSceneService(SearchRuleConfirmationRepository repository) {
        this.repository = repository;
    }

    /**
     * 신고 범위의 제외 후보 규칙을 활성화한다.
     *
     * @return 새로 활성화된 규칙 수(0 또는 1). 0 이면 후보가 이미 적용됐거나 사라진 것이라 호출부가 확정을 막는다(F-12).
     */
    @Override
    public int confirm(long sourceFeedbackId, long ruleId) {
        return repository.activate(sourceFeedbackId, ruleId);
    }
}
