package com.npick.search.application.error;

import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorCode;

/** search 도메인 모듈의 BusinessException. 오류별 예외 클래스를 따로 만들지 않고 ErrorCode 로 구분한다. */
public final class SearchException extends BusinessException {

    public SearchException(ErrorCode errorCode) {
        super(errorCode);
    }

    public SearchException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
