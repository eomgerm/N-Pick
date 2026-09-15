package com.npick.search.application.resolution;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorType;
import com.npick.search.application.error.QueryResolverErrorCode;
import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.QueryResolution.Intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리졸버 해석이 실패해도 검색은 degraded 로 이어진다 (FRD v3.2 §6.2, S15P21A501-50).
 *
 * <p>fallback 은 별도 검색 경로가 아니다 — 같은 BM25 를 빈 해석으로 탈 뿐이라, 여기서 정할 것은 "그 축소를 무엇이라 부르고 어디까지를 fallback 으로 볼 것인가" 하나다.
 *
 * <p>실패 코드를 손으로 열거하지 않고 {@link ErrorType} 으로 갈라 넣는다. 목록으로 쓰면 나중에 추가되는 코드가 어느 쪽인지 아무도 묻지 않은 채 fallback 으로 흘러든다.
 */
class SearchDegradedReasonTest {

    @Test
    @DisplayName("해석에 성공하면 이 경로가 낼 사유가 없다")
    void noReasonWhenResolved() {
        assertThat(SearchDegradedReason.reasonsFor(resolved())).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("dependencyFailures")
    @DisplayName("리졸버 장애는 모두 resolver_fallback 으로 축소한다")
    void fallbackForEveryDependencyFailure(QueryResolverErrorCode failure) {
        assertThat(SearchDegradedReason.reasonsFor(failed(failure)))
                .containsExactly(SearchDegradedReason.RESOLVER_FALLBACK);
    }

    @ParameterizedTest
    @MethodSource("nonDependencyFailures")
    @DisplayName("리졸버가 장애가 아닌 코드를 실어 보내면 fallback 이 아니라 검색 실패다")
    void nonDependencyFailureStopsSearch(QueryResolverErrorCode failure) {
        QueryResolutionResult result = failed(failure);

        assertThatThrownBy(() -> SearchDegradedReason.reasonsFor(result))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(failure);
    }

    @Test
    @DisplayName("사유 이름은 API 계약의 문자열 그대로다")
    void jsonNameMatchesContract() {
        assertThat(SearchDegradedReason.RESOLVER_FALLBACK.jsonName()).isEqualTo("resolver_fallback");
    }

    private static Stream<QueryResolverErrorCode> dependencyFailures() {
        return Arrays.stream(QueryResolverErrorCode.values()).filter(c -> c.type() == ErrorType.SERVICE_UNAVAILABLE);
    }

    private static Stream<QueryResolverErrorCode> nonDependencyFailures() {
        return Arrays.stream(QueryResolverErrorCode.values()).filter(c -> c.type() != ErrorType.SERVICE_UNAVAILABLE);
    }

    private QueryResolutionResult resolved() {
        return new QueryResolutionResult(
                normalization(),
                new QueryResolution(
                        "query-resolver/v2",
                        Intent.SCENE_SEARCH,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        0.9),
                List.of(),
                "query-resolver/v2",
                "prompt/v1",
                "model/v1",
                null);
    }

    private QueryResolutionResult failed(QueryResolverErrorCode failure) {
        return new QueryResolutionResult(normalization(), null, List.of(), null, null, null, failure);
    }

    private QueryNormalization normalization() {
        return new QueryNormalization("서울역 인파", List.of("서울역", "인파"), "kiwi/0.21.0");
    }
}
