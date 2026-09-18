package com.npick.search.application.query.search;

/** 후보 검증 자동 재검색 (S15P21A501-83, FRD F-12). */
public interface VerifyCorrectionCandidatesUseCase {
    VerificationResult verify(long feedbackId, long reviewerId);
}
