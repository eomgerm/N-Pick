package com.npick.search.application.query.search;

import java.util.List;

/** 검증 재검색 결과 요약 — 진입/제외 장면과 적용 규칙 집합(S15P21A501-83 Task 6, F-12 4). */
public record VerificationResult(
        long executionId, List<SceneDiff.Entered> entered, List<SceneDiff.Dropped> dropped,
        List<Long> verificationRuleSet) {}
