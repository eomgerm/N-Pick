package com.npick.pipeline.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum JobErrorCode implements ErrorCode {
    ARTIFACT_PATH_FORBIDDEN(ErrorType.FORBIDDEN, "JOB_403_001", "단계 산출물 저장 범위를 벗어났습니다."),
    NOT_FOUND(ErrorType.NOT_FOUND, "JOB_404_001", "처리 작업을 찾을 수 없습니다."),
    INTEGRATION_UNAVAILABLE(ErrorType.SERVICE_UNAVAILABLE, "JOB_503_001", "단계 산출물 연동이 준비되지 않았습니다.");
    private final ErrorType type;
    private final String code;
    private final String message;

    JobErrorCode(ErrorType type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    public ErrorType type() {
        return type;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
