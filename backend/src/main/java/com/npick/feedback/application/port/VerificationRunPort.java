package com.npick.feedback.application.port;

import java.util.Optional;

/** 확정 근거가 되는 검증 실행을 읽는다 (S15P21A501-84). 성공한 replay 실행이면서 이 신고의 것일 때만 돌려준다 — 다른 신고나 일반 검색 실행을 확정 근거로 쓸 수 없다(F-13 2). */
public interface VerificationRunPort {
    Optional<VerificationRun> find(long executionId, long feedbackId);
}
