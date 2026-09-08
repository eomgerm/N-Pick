package com.npick.search.domain.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * search 도메인의 불변식 위반 (설계 정본 §13).
 *
 * <p>여기에는 ErrorCode 구현만 둔다. 도메인별 RuntimeException 클래스를 따로 만들지 않고 공통 {@code BusinessException} 을 쓴다.
 *
 * <p>이 값들이 {@code IllegalArgumentException} 이면 GlobalExceptionHandler 의 {@code Exception.class} 폴백에 걸려 입력 문제가 500 과
 * error 레벨 스택트레이스가 된다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SearchErrorCode implements ErrorCode {
    NORMALIZED_QUERY_BLANK(ErrorType.BAD_REQUEST, "SRCH_400_001", "정규화된 질의가 비어 있다"),
    NORMALIZATION_VERSION_BLANK(ErrorType.BAD_REQUEST, "SRCH_400_002", "정규화 버전이 비어 있다"),
    FILTER_CONTAINS_NULL(ErrorType.BAD_REQUEST, "SRCH_400_003", "필터에 널 값이 들어 있다"),
    SEARCH_TOKEN_CONTAINS_WHITESPACE(ErrorType.BAD_REQUEST, "SRCH_400_004", "검색 토큰 하나에 공백이 들어 있다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
