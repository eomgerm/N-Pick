package com.npick.search.infrastructure.ai.adapter;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;

import com.npick.search.application.error.SearchErrorCode;
import com.npick.search.application.error.SearchException;

/**
 * 질의 리졸버 호출 실패를 search application 이 소유한 ErrorCode 로 번역한다.
 *
 * <p>Anti-Corruption Layer 의 Translator 자리다. RestClient·Jackson 예외가 이 클래스 바깥으로 새어나가지 않게 막고, 호출측이 fallback 을 판단할 수 있도록
 * 실패를 네 갈래로 나눈다 (FR-QRY-022).
 */
final class QueryResolverExceptionTranslator {

    SearchException translate(Throwable cause) {
        return new SearchException(classify(cause), cause);
    }

    SearchErrorCode classify(Throwable cause) {
        if (hasCause(cause, JacksonException.class)) {
            return SearchErrorCode.RESOLVER_SCHEMA_INVALID;
        }
        if (isRateLimited(cause)) {
            return SearchErrorCode.RESOLVER_RATE_LIMITED;
        }
        if (isTimeout(cause)) {
            return SearchErrorCode.RESOLVER_TIMEOUT;
        }
        if (isNetworkFailure(cause)) {
            return SearchErrorCode.RESOLVER_NETWORK_ERROR;
        }
        return SearchErrorCode.RESOLVER_FAILED;
    }

    private boolean isRateLimited(Throwable cause) {
        for (Throwable current : causeChain(cause)) {
            if (current instanceof RestClientResponseException response
                    && response.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                return true;
            }
        }
        return false;
    }

    /** SocketTimeoutException 은 InterruptedIOException 하위라 {@link #isNetworkFailure} 의 IOException 검사보다 먼저 판정해야 한다. */
    private boolean isTimeout(Throwable cause) {
        return hasCause(cause, InterruptedIOException.class)
                || hasCause(cause, HttpTimeoutException.class)
                || hasCause(cause, TimeoutException.class);
    }

    private boolean isNetworkFailure(Throwable cause) {
        return hasCause(cause, ConnectException.class)
                || hasCause(cause, UnknownHostException.class)
                || hasCause(cause, SSLException.class)
                || hasCause(cause, IOException.class);
    }

    private boolean hasCause(Throwable cause, Class<? extends Throwable> type) {
        for (Throwable current : causeChain(cause)) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    /** 예외 체인이 순환해도 무한 루프에 빠지지 않게 방문한 예외를 기억한다. */
    private List<Throwable> causeChain(Throwable cause) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = cause; current != null && visited.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
