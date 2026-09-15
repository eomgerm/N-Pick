package com.npick.search.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum DenseSearchErrorCode implements ErrorCode {
    DATABASE_UNAVAILABLE;

    public ErrorType type() {
        return ErrorType.SERVICE_UNAVAILABLE;
    }

    public String code() {
        return "SRCH_503_301";
    }

    public String message() {
        return "검색 데이터베이스 조회에 실패했습니다";
    }
}
