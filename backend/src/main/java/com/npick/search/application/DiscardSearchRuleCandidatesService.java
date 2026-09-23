package com.npick.search.application;

import org.springframework.stereotype.Service;

import com.npick.search.domain.repository.SearchRuleConfirmationRepository;

/**
 * 해석·제외 규칙 대기 후보 폐기 — search 도메인이 공개하는 UseCase (S15P21A501-281).
 *
 * <p>feedback 오케스트레이터가 no_action 종료 트랜잭션 안에서 호출한다. 이 신고가 만든 미확정 규칙 후보({@code search_rule.active=false},
 * patch_parse·exclude_scene 공통)를 지운다. 트랜잭션 경계는 호출부가 가진다.
 */
@Service
public class DiscardSearchRuleCandidatesService implements DiscardSearchRuleCandidatesUseCase {

    private final SearchRuleConfirmationRepository repository;

    public DiscardSearchRuleCandidatesService(SearchRuleConfirmationRepository repository) {
        this.repository = repository;
    }

    @Override
    public int discardPending(long sourceFeedbackId) {
        return repository.discardPending(sourceFeedbackId);
    }
}
