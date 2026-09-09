package com.npick.feedback.domain.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum FeedbackErrorCode implements ErrorCode {
    RESULT_NOT_FOUND(ErrorType.NOT_FOUND, "FEEDBACK_404_001", "신고할 수 있는 저장된 검색 결과가 아닙니다."),
    FEEDBACK_NOT_FOUND(ErrorType.NOT_FOUND, "FEEDBACK_404_002", "문의를 찾을 수 없습니다."),
    ALREADY_CLAIMED(ErrorType.CONFLICT, "FEEDBACK_409_001", "이미 검수가 시작된 문의입니다."),
    NOT_EDITABLE(ErrorType.CONFLICT, "FEEDBACK_409_002", "검수가 시작되어 수정할 수 없습니다."),
    NOT_RESOLVABLE(ErrorType.CONFLICT, "FEEDBACK_409_003", "검수 중인 문의만 처리할 수 있습니다."),
    NOT_OWNER(ErrorType.FORBIDDEN, "FEEDBACK_403_001", "본인이 접수한 문의만 수정할 수 있습니다."),
    NOT_REVIEWER(ErrorType.FORBIDDEN, "FEEDBACK_403_002", "담당 검수자만 처리할 수 있습니다."),
    NOTE_REQUIRED(ErrorType.BAD_REQUEST, "FEEDBACK_400_001", "이 처리 결과는 사유가 필요합니다."),
    INVALID_RESOLUTION(ErrorType.BAD_REQUEST, "FEEDBACK_400_002", "알 수 없는 처리 결과입니다.");

    private final ErrorType type;
    private final String code;
    private final String message;

    FeedbackErrorCode(ErrorType type, String code, String message) {
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
