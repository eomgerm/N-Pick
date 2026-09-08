package com.npick.search.infrastructure.ai.adapter;

import org.springframework.stereotype.Component;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.QueryResolverErrorCode;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.port.QueryResolverPort;
import com.npick.search.infrastructure.ai.client.QueryResolverClient;
import com.npick.search.infrastructure.ai.client.request.QueryResolutionApiRequest;
import com.npick.search.infrastructure.ai.client.response.QueryResolutionApiResponse;

/**
 * 질의 리졸버를 호출하는 어댑터.
 *
 * <p>동기 호출이고 재시도하지 않는다. 실패는 {@link QueryResolverExceptionTranslator} 가 네 갈래 ErrorCode 로 번역하고, 원 검색어 BM25 로 전환할지는 검색
 * 오케스트레이션이 판단한다 (FRD v3.1 §6.2).
 *
 * <p>구현 교체(EC2 local 모델 / 승인된 GMS)는 코드가 아니라 {@code spring.http.serviceclient.queryResolver.base-url} 로 한다.
 */
@Component
class QueryResolverAdapter implements QueryResolverPort {

    private final QueryResolverClient client;
    private final QueryResolverExceptionTranslator exceptionTranslator = new QueryResolverExceptionTranslator();

    QueryResolverAdapter(QueryResolverClient client) {
        this.client = client;
    }

    @Override
    public QueryResolutionResult resolve(String rawQuery) {
        QueryResolutionApiResponse response;
        try {
            response = client.resolve(new QueryResolutionApiRequest(rawQuery));
        } catch (RuntimeException ex) {
            throw exceptionTranslator.translate(ex);
        }

        if (response == null) {
            throw new BusinessException(QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID);
        }
        try {
            return response.toResult();
        } catch (RuntimeException ex) {
            // 응답은 왔지만 우리가 아는 schema 가 아니다.
            throw new BusinessException(QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID, ex);
        }
    }
}
