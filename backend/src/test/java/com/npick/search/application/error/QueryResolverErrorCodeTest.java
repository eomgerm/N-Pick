package com.npick.search.application.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.common.error.ErrorType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class QueryResolverErrorCodeTest {

    @Test
    @DisplayName("질의 리졸버 오류는 기존 코드와 사용자 안내 문구를 유지한다")
    void exposesStableCodesAndUserGuidance() {
        assertThat(QueryResolverErrorCode.values())
                .extracting(
                        errorCode -> errorCode.name(),
                        QueryResolverErrorCode::type,
                        QueryResolverErrorCode::code,
                        QueryResolverErrorCode::message)
                .containsExactly(
                        tuple(
                                "RESOLVER_TIMEOUT",
                                ErrorType.SERVICE_UNAVAILABLE,
                                "SRCH_503_001",
                                "검색 서비스 응답이 늦어지고 있어요. 검색어를 수정할 필요는 없어요. 잠시 후 다시 시도해 주세요."),
                        tuple(
                                "RESOLVER_SCHEMA_INVALID",
                                ErrorType.SERVICE_UNAVAILABLE,
                                "SRCH_503_002",
                                "검색 서비스 응답을 처리하기 어려워요. 검색어를 수정할 필요는 없어요. 잠시 후 다시 시도해 주세요."),
                        tuple(
                                "RESOLVER_RATE_LIMITED",
                                ErrorType.SERVICE_UNAVAILABLE,
                                "SRCH_503_003",
                                "현재 검색 요청이 많아 잠시 처리하기 어려워요. 검색어를 수정할 필요는 없어요. 잠시 후 다시 시도해 주세요."),
                        tuple(
                                "RESOLVER_NETWORK",
                                ErrorType.SERVICE_UNAVAILABLE,
                                "SRCH_503_004",
                                "검색 서비스에 잠시 연결하기 어려워요. 검색어를 수정할 필요는 없어요. 잠시 후 다시 시도해 주세요."),
                        tuple(
                                "RESOLVER_FAILED",
                                ErrorType.SERVICE_UNAVAILABLE,
                                "SRCH_503_009",
                                "검색 서비스를 잠시 이용하기 어려워요. 검색어를 수정할 필요는 없어요. 잠시 후 다시 시도해 주세요."),
                        tuple(
                                "QUERY_NOT_NORMALIZABLE",
                                ErrorType.BAD_REQUEST,
                                "SRCH_400_101",
                                "입력한 내용만으로는 검색하기 어려워요. 인물, 장소, 사건처럼 찾으려는 대상을 포함해 다시 입력해 주세요."));
    }
}
