package com.npick.search.domain.repository;

import java.util.Optional;

import com.npick.search.domain.model.ParseRuleCandidate;

/**
 * patch_parse 규칙 후보를 쓴다. 읽기 전용인 {@link ParseRuleRepository}(활성 규칙 판정용)와 책임이 다르므로 별도 포트로 둔다.
 *
 * <p>후보는 {@code active=false} 로만 저장된다. 켜는 것(-84)과 발화(-49)는 이 포트의 일이 아니다.
 */
public interface ParseRuleCandidateRepository {

    /** 후보를 저장하고 생성된 {@code search_rule_id} 를 준다. */
    long save(ParseRuleCandidate candidate);

    /** 같은 신고·요청키로 이미 만든 후보의 id. 멱등 처리에 쓴다. */
    Optional<Long> findId(long sourceFeedbackId, String requestKey);
}
