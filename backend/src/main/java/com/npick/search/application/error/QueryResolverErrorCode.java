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
 * <p><b>enum 이름이 리졸버의 {@code ResolverCallError.category} 문자열과 1:1 이어야 한다.</b> 정본은
 * {@code ai/src/npick_worker/query_resolver/ollama_backend.py} · {@code gms_backend.py} 의 상수 ({@code _TIMEOUT} ·
 * {@code _RATE_LIMITED} · {@code _NETWORK})다. 이름이 어긋나면 응답의 category 를 해석하지 못해 모든 실패가 {@link #RESOLVER_FAILED} 로 뭉개진다 —
 * 오류가 나지 않아 못 잡는다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum QueryResolverErrorCode implements ErrorCode {
    /** 리졸버 category {@code RESOLVER_TIMEOUT}. */
    RESOLVER_TIMEOUT(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_001", "질의 리졸버 응답이 제한 시간을 넘었다"),
    /** 리졸버 category {@code RESOLVER_SCHEMA_INVALID}, 또는 BE 가 응답을 파싱하지 못한 경우. */
    RESOLVER_SCHEMA_INVALID(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_002", "질의 리졸버 응답이 schema 와 맞지 않는다"),
    /** 리졸버 category {@code RESOLVER_RATE_LIMITED}. */
    RESOLVER_RATE_LIMITED(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_003", "질의 리졸버 호출 한도를 넘었다"),
    /** 리졸버 category {@code RESOLVER_NETWORK}. 이름을 늘리면 category 해석이 깨진다. */
    RESOLVER_NETWORK(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_004", "질의 리졸버에 연결할 수 없다"),
    /** 리졸버 category {@code RESOLVER_FAILED}, 또는 우리가 분류하지 못한 실패. */
    RESOLVER_FAILED(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_009", "질의 리졸버 호출에 실패했다"),

    /**
     * 정규화할 수 없는 질의. 리졸버가 400 으로 답한 경우다.
     *
     * <p>의존성 장애가 아니라 <b>사용자 입력 문제</b>다. 빈 값·기호만·불용어만인 질의가 여기 걸리며, 지문을 만들 수 없어 검색 자체가 성립하지 않는다 (FRD v3.1 §6.2 의 fallback
     * 대상이 아니다).
     */
    QUERY_NOT_NORMALIZABLE(ErrorType.BAD_REQUEST, "SRCH_400_101", "검색어에서 검색할 수 있는 단어를 찾지 못했다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
