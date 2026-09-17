package com.npick.search.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum SearchExecutionErrorCode implements ErrorCode {
    NOT_FOUND(ErrorType.NOT_FOUND, "SEARCH_404_001", "검색 실행 기록을 찾을 수 없습니다.");

    private final ErrorType type;
    private final String code;
    private final String message;

    SearchExecutionErrorCode(ErrorType type, String code, String message) {
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
