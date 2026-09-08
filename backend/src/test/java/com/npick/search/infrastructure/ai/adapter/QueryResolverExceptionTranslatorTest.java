package com.npick.search.infrastructure.ai.adapter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;

import com.npick.search.application.error.SearchErrorCode;
import com.npick.search.application.error.SearchException;

import static org.assertj.core.api.Assertions.assertThat;

class QueryResolverExceptionTranslatorTest {

    private final QueryResolverExceptionTranslator translator = new QueryResolverExceptionTranslator();

    @Test
    @DisplayName("read timeout 은 RESOLVER_TIMEOUT 으로 분류한다")
    void classifiesReadTimeout() {
        Throwable cause = new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out"));

        assertThat(translator.classify(cause)).isEqualTo(SearchErrorCode.RESOLVER_TIMEOUT);
    }

    @Test
    @DisplayName("접속 실패와 DNS 실패는 RESOLVER_NETWORK_ERROR 로 분류한다")
    void classifiesNetworkFailure() {
        assertThat(translator.classify(
                        new ResourceAccessException("refused", new ConnectException("Connection refused"))))
                .isEqualTo(SearchErrorCode.RESOLVER_NETWORK_ERROR);
        assertThat(translator.classify(
                        new ResourceAccessException("dns", new UnknownHostException("resolver.internal"))))
                .isEqualTo(SearchErrorCode.RESOLVER_NETWORK_ERROR);
        assertThat(translator.classify(new IOException("broken pipe")))
                .isEqualTo(SearchErrorCode.RESOLVER_NETWORK_ERROR);
    }

    @Test
    @DisplayName("429 는 RESOLVER_RATE_LIMITED 로 분류한다")
    void classifiesRateLimit() {
        Throwable cause = new RestClientResponseException(
                "Too Many Requests", HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY, null, null);

        assertThat(translator.classify(cause)).isEqualTo(SearchErrorCode.RESOLVER_RATE_LIMITED);
    }

    @Test
    @DisplayName("JSON 파싱 실패는 RESOLVER_SCHEMA_INVALID 로 분류한다")
    void classifiesSchemaFailure() {
        Throwable cause = new IllegalStateException("convert failed", new StubJacksonException());

        assertThat(translator.classify(cause)).isEqualTo(SearchErrorCode.RESOLVER_SCHEMA_INVALID);
    }

    @Test
    @DisplayName("분류할 수 없는 실패는 RESOLVER_FAILED 로 떨어진다")
    void fallsBackToGenericFailure() {
        assertThat(translator.classify(new IllegalStateException("boom"))).isEqualTo(SearchErrorCode.RESOLVER_FAILED);
    }

    @Test
    @DisplayName("순환 참조하는 예외 체인에서도 무한 루프에 빠지지 않는다")
    void survivesCyclicCauseChain() {
        Throwable first = new IllegalStateException("first");
        Throwable second = new IllegalStateException("second", first);
        first.initCause(second);

        assertThat(translator.classify(first)).isEqualTo(SearchErrorCode.RESOLVER_FAILED);
    }

    @Test
    @DisplayName("translate 는 원인 예외를 보존한 SearchException 을 만든다")
    void translateKeepsCause() {
        Throwable cause = new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out"));

        SearchException translated = translator.translate(cause);

        assertThat(translated.errorCode()).isEqualTo(SearchErrorCode.RESOLVER_TIMEOUT);
        assertThat(translated.getCause()).isSameAs(cause);
    }

    private static final class StubJacksonException extends JacksonException {
        private StubJacksonException() {
            super("malformed json");
        }
    }
}
