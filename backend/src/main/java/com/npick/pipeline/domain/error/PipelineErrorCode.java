package com.npick.pipeline.domain.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum PipelineErrorCode implements ErrorCode {
    INVALID_RESULT(ErrorType.BAD_REQUEST, "JOB_400_001", "단계 결과 형식이 올바르지 않습니다."),
    INVALID_STATE(ErrorType.CONFLICT, "JOB_409_001", "현재 상태에서 단계를 실행하거나 반영할 수 없습니다."),
    STALE_LEASE(ErrorType.CONFLICT, "JOB_409_002", "단계 실행 권한이 만료되거나 회수되었습니다."),
    IDEMPOTENCY_CONFLICT(ErrorType.CONFLICT, "JOB_409_003", "같은 결과 키의 내용이 다릅니다."),
    VERSION_CONFLICT(ErrorType.CONFLICT, "JOB_409_004", "처리 실행의 기대 버전을 확인할 수 없습니다.");
    private final ErrorType type;
    private final String code;
    private final String message;

    PipelineErrorCode(ErrorType type, String code, String message) {
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
