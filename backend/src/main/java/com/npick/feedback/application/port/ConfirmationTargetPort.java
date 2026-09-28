package com.npick.feedback.application.port;

import java.util.Optional;

/** 확정 전제 판정용 신고 상태를 읽는다 (S15P21A501-84). */
public interface ConfirmationTargetPort {
    Optional<ConfirmationTarget> find(long feedbackId);
}
