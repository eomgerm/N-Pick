package com.npick.search.application.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * 질의 리졸버 호출 실패 (설계 정본 §13 의 application ErrorCode).
 *
 * <p>{@code search.domain.error.SearchErrorCode} 는 검색 도메인의 불변식 위반을 다룬다. 이쪽은 외부 의존성 실패라 계층이 다르다.
 *
 * <p>네 갈래로 나누는 이유는 호출측이 FRD v3.1 §6.2 에 따라 원 검색어 BM25 로 전환하면서 degraded 사유를 §7.2 기록에 남겨야 하기 때문이다. 리졸버 모듈의
 * {@code ResolverCallError.category} 와 같은 구분이다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum QueryResolverErrorCode implements ErrorCode {
    RESOLVER_TIMEOUT(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_001", "질의 리졸버 응답이 제한 시간을 넘었다"),
    RESOLVER_SCHEMA_INVALID(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_002", "질의 리졸버 응답이 schema 와 맞지 않는다"),
    RESOLVER_RATE_LIMITED(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_003", "질의 리졸버 호출 한도를 넘었다"),
    RESOLVER_NETWORK_ERROR(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_004", "질의 리졸버에 연결할 수 없다"),
    RESOLVER_FAILED(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_009", "질의 리졸버 호출에 실패했다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
