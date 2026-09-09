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

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.QueryResolverErrorCode;

/**
 * 질의 리졸버에 닿지 못한 실패를 search application 이 소유한 ErrorCode 로 번역한다.
 *
 * <p>Anti-Corruption Layer 의 Translator 자리다. RestClient·Jackson 예외가 이 클래스 바깥으로 새어나가지 않게 막는다.
 *
 * <p>리졸버가 <b>응답을 준</b> 경우의 해석 실패는 여기 오지 않는다 — 200 본문의 {@code error.category} 로 오며 {@code QueryResolutionApiResponse} 가
 * 다룬다 (FRD v3.1 §6.2).
 */
final class QueryResolverExceptionTranslator {

    BusinessException translate(Throwable cause) {
        return new BusinessException(classify(cause), cause);
    }

    QueryResolverErrorCode classify(Throwable cause) {
        if (hasCause(cause, JacksonException.class)) {
            return QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID;
        }
        // 4xx 는 우리 요청이 잘못됐다는 뜻이므로 의존성 장애로 분류하지 않는다. 특히 리졸버는
        // 정규화 불가 질의("!!!" 등)에 400 을 준다 — 그걸 503 으로 만들면 사용자가 자기 입력
        // 문제를 서버 장애로 안내받는다.
        QueryResolverErrorCode clientError = classifyClientError(cause);
        if (clientError != null) {
            return clientError;
        }
        if (isTimeout(cause)) {
            return QueryResolverErrorCode.RESOLVER_TIMEOUT;
        }
        if (isNetworkFailure(cause)) {
            return QueryResolverErrorCode.RESOLVER_NETWORK;
        }
        return QueryResolverErrorCode.RESOLVER_FAILED;
    }

    private QueryResolverErrorCode classifyClientError(Throwable cause) {
        for (Throwable current : causeChain(cause)) {
            if (!(current instanceof RestClientResponseException response)) {
                continue;
            }
            // 비교는 isSameCodeAs 하나로 통일한다. HttpStatus.resolve 는 표준에 없는 코드에
            // null 을 주므로 == 비교와 섞으면 판정 기준이 두 가지가 된다.
            if (response.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                return QueryResolverErrorCode.RESOLVER_RATE_LIMITED;
            }
            if (response.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
                return QueryResolverErrorCode.QUERY_NOT_NORMALIZABLE;
            }
            if (response.getStatusCode().is4xxClientError()) {
                // 401·404·422 등은 배선이 틀린 것이다. 재시도해도 소용없으므로 장애와 구분한다.
                return QueryResolverErrorCode.RESOLVER_FAILED;
            }
        }
        return null;
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
