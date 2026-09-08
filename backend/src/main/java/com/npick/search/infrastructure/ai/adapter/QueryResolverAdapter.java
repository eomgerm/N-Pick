package com.npick.search.infrastructure.ai.adapter;

import org.springframework.stereotype.Component;

import com.npick.search.application.error.SearchErrorCode;
import com.npick.search.application.error.SearchException;
import com.npick.search.application.port.QueryResolution;
import com.npick.search.application.port.QueryResolutionRequest;
import com.npick.search.application.port.QueryResolverPort;
import com.npick.search.infrastructure.ai.client.QueryResolverClient;
import com.npick.search.infrastructure.ai.client.request.QueryResolutionApiRequest;
import com.npick.search.infrastructure.ai.client.response.QueryResolutionApiResponse;

/**
 * 질의 리졸버를 호출하는 어댑터.
 *
 * <p>동기 호출이고 재시도하지 않는다. timeout 이면 즉시 실패한다 (FR-QRY-023). 실패는 {@link QueryResolverExceptionTranslator} 가 네 갈래 ErrorCode
 * 로 번역하고, raw query BM25 fallback 으로 이어갈지는 호출측이 판단한다 (FR-QRY-022).
 *
 * <p>리졸버 구현 교체(local 모델 / 승인된 GMS)는 코드가 아니라 {@code spring.http.serviceclient.queryResolver.base-url} 로 한다.
 */
@Component
class QueryResolverAdapter implements QueryResolverPort {

    private final QueryResolverClient client;
    private final QueryResolverExceptionTranslator exceptionTranslator = new QueryResolverExceptionTranslator();

    QueryResolverAdapter(QueryResolverClient client) {
        this.client = client;
    }

    @Override
    public QueryResolution resolve(QueryResolutionRequest request) {
        QueryResolutionApiResponse response;
        try {
            response = client.resolve(QueryResolutionApiRequest.from(request));
        } catch (RuntimeException ex) {
            throw exceptionTranslator.translate(ex);
        }

        if (response == null) {
            throw new SearchException(SearchErrorCode.RESOLVER_SCHEMA_INVALID);
        }
        try {
            return response.toResolution();
        } catch (RuntimeException ex) {
            // 응답은 왔지만 우리가 아는 schema 가 아니다.
            throw new SearchException(SearchErrorCode.RESOLVER_SCHEMA_INVALID, ex);
        }
    }
}
