package com.npick.clip.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum ClipQueryErrorCode implements ErrorCode {
    INVALID_STATUS(
            ErrorType.BAD_REQUEST,
            "CLIP_QUERY_400_001",
            "status는 queued, running, failed, succeeded, no_run 중 선택해야 합니다."),
    CLIP_NOT_FOUND(ErrorType.NOT_FOUND, "CLIP_QUERY_404", "클립을 찾을 수 없습니다."),
    INVALID_PAGE(ErrorType.BAD_REQUEST, "CLIP_QUERY_400", "페이지는 0 이상, 크기는 1~100이고 page × size는 2147483647 이하여야 합니다.");
    private final ErrorType type;
    private final String code;
    private final String message;

    ClipQueryErrorCode(ErrorType type, String code, String message) {
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
