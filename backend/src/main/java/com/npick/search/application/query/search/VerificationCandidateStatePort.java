package com.npick.search.application.query.search;

import java.util.List;

/** 롤백 검증 트랜잭션 안에서 후보를 임시 반영하고 활성 해석 규칙을 읽는다. */
public interface VerificationCandidateStatePort {

    void flip(PendingCandidates candidates);

    List<Long> readActivePatchRuleIds();
}
