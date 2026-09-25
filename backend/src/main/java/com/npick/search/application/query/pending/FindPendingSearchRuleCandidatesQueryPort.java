package com.npick.search.application.query.pending;

/** 대기 중인 규칙 후보의 본문을 읽는 조회 계약 (S15P21A501-317, 설계 정본 §9 의 QueryPort). */
public interface FindPendingSearchRuleCandidatesQueryPort {

    PendingSearchRuleCandidates findPending(long sourceFeedbackId);
}
