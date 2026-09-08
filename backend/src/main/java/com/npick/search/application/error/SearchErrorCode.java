package com.npick.search.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * search 유스케이스 조정과 외부 의존성 실패를 표현하는 ErrorCode.
 *
 * <p>resolver 실패는 네 갈래로 나뉜다. 호출측은 이 값을 보고 raw query BM25 fallback 으로 이어갈지 판단한다 (FR-QRY-022).
 */
public enum SearchErrorCode implements ErrorCode {
    RESOLVER_TIMEOUT(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_001", "Query resolver timed out"),
    RESOLVER_SCHEMA_INVALID(
            ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_002", "Query resolver returned an invalid resolution schema"),
    RESOLVER_RATE_LIMITED(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_003", "Query resolver rate limit exceeded"),
    RESOLVER_NETWORK_ERROR(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_004", "Query resolver is unreachable"),
    RESOLVER_FAILED(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_009", "Query resolver call failed");

    private final ErrorType type;
    private final String code;
    private final String message;

    SearchErrorCode(ErrorType type, String code, String message) {
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
