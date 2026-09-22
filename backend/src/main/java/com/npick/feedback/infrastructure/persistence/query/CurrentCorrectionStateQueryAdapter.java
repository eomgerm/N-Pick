package com.npick.feedback.infrastructure.persistence.query;

import org.springframework.stereotype.Repository;

import com.npick.common.persistence.CorrectionStateFingerprint;
import com.npick.feedback.application.port.CurrentCorrectionStatePort;

/**
 * 확정 시점 현재 상태 지문 (S15P21A501-84, F-13 3·4). 지문 정의는 검증(-83)과 대칭이어야 하므로 공용 {@link CorrectionStateFingerprint} 에 위임한다 — 두
 * 쪽이 같은 계산을 봐야 확정이 영구 불일치로 막히지 않는다(S15P21A501-83 §5).
 */
@Repository
class CurrentCorrectionStateQueryAdapter implements CurrentCorrectionStatePort {

    private final CorrectionStateFingerprint fingerprint;

    CurrentCorrectionStateQueryAdapter(CorrectionStateFingerprint fingerprint) {
        this.fingerprint = fingerprint;
    }

    @Override
    public String currentFingerprint(long feedbackId) {
        return fingerprint.compute(feedbackId);
    }
}
