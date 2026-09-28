package com.npick.search.application.query.pending;

import org.springframework.stereotype.Service;

/** 대기 중인 규칙 후보 조회 (S15P21A501-317). 트랜잭션 경계는 호출부가 가진다. */
@Service
public class PendingSearchRuleCandidateQueryService implements ListPendingSearchRuleCandidatesUseCase {

    private final FindPendingSearchRuleCandidatesQueryPort queryPort;

    public PendingSearchRuleCandidateQueryService(FindPendingSearchRuleCandidatesQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    @Override
    public PendingSearchRuleCandidates listPending(long sourceFeedbackId) {
        return queryPort.findPending(sourceFeedbackId);
    }
}
