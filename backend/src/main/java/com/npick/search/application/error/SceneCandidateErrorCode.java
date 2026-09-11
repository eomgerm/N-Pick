package com.npick.search.application.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * 후보 조회 실행 실패 (설계 정본 §13 의 application ErrorCode).
 *
 * <p>{@code search.domain.error.SearchErrorCode} 와 계층이 다르다. 그쪽은 사용자가 준 값이 도메인 불변식을 어긴 경우이고, 여기는 <b>우리 코드가 만든 값</b>이 계약을
 * 어긴 경우다. 그래서 전부 5xx 다 — 사용자에게 "당신 입력이 잘못됐다" 고 안내하면 진짜 원인을 가린다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SceneCandidateErrorCode implements ErrorCode {

    /**
     * 검색 토큰 하나에 공백이 들어 있다.
     *
     * <p>이 토큰은 사용자가 친 문자열이 아니라 워커의 Kiwi 형태소 분석이 만든 값이다. 형태소는 공백을 품지 않으므로 이 값이 왔다는 것은 토크나이저나 그 배선이 망가졌다는 뜻이다.
     * {@code BAD_REQUEST} 로 두면 편집기자가 "검색어가 잘못됐다" 는 400 을 받고, 정작 서버 결함은 5xx 로 집계되지 않는다.
     */
    SEARCH_TOKEN_CONTAINS_WHITESPACE(ErrorType.INTERNAL_SERVER_ERROR, "SRCH_500_001", "검색 토큰을 만드는 과정이 잘못됐다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
