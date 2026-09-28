package com.npick.search.application;

import org.springframework.stereotype.Service;

import com.npick.search.domain.repository.SearchRuleConfirmationRepository;

/**
 * 해석 교정 규칙(patch_parse) 확정 — search 도메인이 공개하는 UseCase (S15P21A501-84, F-13).
 *
 * <p>feedback 오케스트레이터가 확정 트랜잭션 안에서 호출한다. 승인 후보를 활성화하고, 교체 대상이 있으면 함께 비활성화한다. 이 UseCase 자체는 트랜잭션을 열지 않는다 — 태그·규칙·신고 갱신이
 * 한 트랜잭션이어야 하므로 경계는 호출부가 가진다(F-13 "한 DB 트랜잭션").
 */
@Service
public class ConfirmParseRuleService implements ConfirmParseRuleUseCase {

    private final SearchRuleConfirmationRepository repository;

    public ConfirmParseRuleService(SearchRuleConfirmationRepository repository) {
        this.repository = repository;
    }

    /**
     * 후보 규칙을 활성화하고, 교체 대상이 지정됐으면 비활성화한다.
     *
     * @param replacedRuleId 교체 대상 규칙. 없으면 {@code null}
     * @return 후보 활성화와 (교체 지정 시) 교체 대상 비활성화가 모두 적용되면 1, 아니면 0. 0 이면 후보나 교체 대상이 이미 적용됐거나 사라진 것이라 호출부가 확정을 막는다(F-12). 교체
     *     대상이 없거나 이미 비활성인데 후보만 활성화되는 반쪽 교체도 0 으로 막는다.
     */
    @Override
    public int confirm(long sourceFeedbackId, long approvedRuleId, Long replacedRuleId) {
        int activated = repository.activate(sourceFeedbackId, approvedRuleId);
        if (replacedRuleId != null && repository.deactivate(replacedRuleId) != 1) {
            return 0;
        }
        return activated;
    }
}
