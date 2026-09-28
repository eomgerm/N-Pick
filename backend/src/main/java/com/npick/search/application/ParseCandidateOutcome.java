package com.npick.search.application;

/** 후보 생성 결과. {@code created} 는 이번 요청이 새로 만들었는지, 멱등 재생으로 기존 후보를 돌려준 것인지 구분한다 — 프레젠테이션이 201 과 200 을 가른다. */
public record ParseCandidateOutcome(long searchRuleId, boolean created) {

    public static ParseCandidateOutcome created(long searchRuleId) {
        return new ParseCandidateOutcome(searchRuleId, true);
    }

    public static ParseCandidateOutcome existing(long searchRuleId) {
        return new ParseCandidateOutcome(searchRuleId, false);
    }
}
