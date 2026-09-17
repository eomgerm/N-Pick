package com.npick.feedback.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/** 교정 확정(S15P21A501-84, F-13) 실패. 확정은 태그·규칙·신고를 걸쳐 조정하므로 application 계층이 오류를 소유한다(설계 정본 §16). */
public enum ConfirmCorrectionErrorCode implements ErrorCode {
    /** 검수자가 아니다. */
    EDITOR_FORBIDDEN(ErrorType.FORBIDDEN, "CONFIRM_403_001", "검수자만 교정을 확정할 수 있다"),
    /** 담당 검수자가 아니다. */
    NOT_REVIEWER(ErrorType.FORBIDDEN, "CONFIRM_403_002", "담당 검수자만 교정을 확정할 수 있다"),
    /** 대상 신고가 없다. */
    FEEDBACK_NOT_FOUND(ErrorType.NOT_FOUND, "CONFIRM_404_001", "대상 신고를 찾을 수 없다"),
    /** 지정한 검증 실행이 이 신고의 것이 아니거나 성공하지 않았다. */
    NOT_VERIFICATION_RUN(ErrorType.NOT_FOUND, "CONFIRM_404_002", "확정할 검증 실행을 찾을 수 없다"),
    /** 검수 중이 아니다. */
    NOT_REVIEWING(ErrorType.CONFLICT, "CONFIRM_409_001", "검수 중인 신고만 확정할 수 있다"),
    /** 태그·해석 교정으로 처리된 신고가 아니다(장면 제외는 S15P21A501-85, no_action·deferred 는 확정 대상 아님). */
    NOT_A_CORRECTION(ErrorType.CONFLICT, "CONFIRM_409_002", "태그·해석 교정으로 처리된 신고가 아니다"),
    /** 검증 이후 관련 상태가 바뀌었다. 다시 검증해야 한다(F-13 4). */
    NEEDS_REVERIFICATION(ErrorType.CONFLICT, "CONFIRM_409_003", "검증 이후 상태가 바뀌어 다시 검증해야 한다"),
    /** 장면 제외 확정 직전에 대상 장면이 재처리로 사라졌다(F-14). 승격을 중단하고 신고는 reviewing 을 유지한다. */
    TARGET_SCENE_GONE(ErrorType.CONFLICT, "CONFIRM_409_004", "대상 장면이 재처리로 사라졌다 — 다시 검증해야 한다");

    private final ErrorType type;
    private final String code;
    private final String message;

    ConfirmCorrectionErrorCode(ErrorType type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
