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
 * <p>구현 교체(EC2 local 모델 / 외부 제공자)는 코드가 아니라 {@code spring.http.serviceclient.queryResolver.base-url} 로 한다. <b>이 어댑터에는 전송
 * 승인 판정이 없다.</b> 설정이 외부 제공자를 가리키면 원문 검색어가 그대로 나간다 — FRD v3.1 §6.4 의 허용 목록과 승인 기록은 {@code S15P21A501-134} 에서 붙인다. 그 전까지
 * 외부 제공자 설정은 승인된 환경에서만 쓴다.
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
            // 정규화조차 읽지 못한 경우다. 이때는 BM25 fallback 재료가 없어 검색을 이어갈 수 없다.
            // 해석만 못 읽은 경우는 toResult() 안에서 failure 로 바뀌어 정상 반환된다.
            throw new BusinessException(QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID, ex);
        }
    }
}
