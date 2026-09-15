package com.npick.search.application;

import org.springframework.stereotype.Service;

import com.npick.search.domain.repository.SearchRuleConfirmationRepository;

/**
 * 해석 교정 규칙(patch_parse) 확정 — search 도메인이 공개하는 UseCase (S15P21A501-84, F-13).
 *
 * <p>feedback 오케스트레이터가 확정 트랜잭션 안에서 호출한다. 승인 후보를 활성화하고, 교체 대상이 있으면 함께 비활성화한다. 이 UseCase 자체는 트랜잭션을 열지 않는다 — 태그·규칙·신고
 * 갱신이 한 트랜잭션이어야 하므로 경계는 호출부가 가진다(F-13 "한 DB 트랜잭션").
 */
@Service
public class ConfirmParseRuleUseCase {

    private final SearchRuleConfirmationRepository repository;

    public ConfirmParseRuleUseCase(SearchRuleConfirmationRepository repository) {
        this.repository = repository;
    }

    /**
     * 후보 규칙을 활성화하고, 교체 대상이 지정됐으면 비활성화한다.
     *
     * @param replacedRuleId 교체 대상 규칙. 없으면 {@code null}
     */
    public void confirm(long sourceFeedbackId, long approvedRuleId, Long replacedRuleId) {
        repository.activate(sourceFeedbackId, approvedRuleId);
        if (replacedRuleId != null) {
            repository.deactivate(replacedRuleId);
        }
    }
}
