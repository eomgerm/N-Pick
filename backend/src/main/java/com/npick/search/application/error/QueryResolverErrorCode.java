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
    RESOLVER_TIMEOUT(
            ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_001", "검색 서비스 응답이 늦어지고 있어요. 검색어를 수정할 필요는 없어요. 잠시 후 다시 시도해 주세요."),
    /** 리졸버 category {@code RESOLVER_SCHEMA_INVALID}, 또는 BE 가 응답을 파싱하지 못한 경우. */
    RESOLVER_SCHEMA_INVALID(
            ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_002", "검색 서비스 응답을 처리하기 어려워요. 검색어를 수정할 필요는 없어요. 잠시 후 다시 시도해 주세요."),
    /** 리졸버 category {@code RESOLVER_RATE_LIMITED}. */
    RESOLVER_RATE_LIMITED(
            ErrorType.SERVICE_UNAVAILABLE,
            "SRCH_503_003",
            "현재 검색 요청이 많아 잠시 처리하기 어려워요. 검색어를 수정할 필요는 없어요. 잠시 후 다시 시도해 주세요."),
    /** 리졸버 category {@code RESOLVER_NETWORK}. 이름을 늘리면 category 해석이 깨진다. */
    RESOLVER_NETWORK(
            ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_004", "검색 서비스에 잠시 연결하기 어려워요. 검색어를 수정할 필요는 없어요. 잠시 후 다시 시도해 주세요."),
    /** 리졸버 category {@code RESOLVER_FAILED}, 또는 우리가 분류하지 못한 실패. */
    RESOLVER_FAILED(
            ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_009", "검색 서비스를 잠시 이용하기 어려워요. 검색어를 수정할 필요는 없어요. 잠시 후 다시 시도해 주세요."),

    /**
     * 정규화할 수 없는 질의. 리졸버가 400 으로 답한 경우다.
     *
     * <p>의존성 장애가 아니라 <b>사용자 입력 문제</b>다. 빈 값·기호만·불용어만인 질의가 여기 걸리며, 지문을 만들 수 없어 검색 자체가 성립하지 않는다 (FRD v3.1 §6.2 의 fallback
     * 대상이 아니다).
     */
    QUERY_NOT_NORMALIZABLE(
            ErrorType.BAD_REQUEST, "SRCH_400_101", "입력한 내용만으로는 검색하기 어려워요. 인물, 장소, 사건처럼 찾으려는 대상을 포함해 다시 입력해 주세요.");

    private final ErrorType type;
    private final String code;
    private final String message;
}
