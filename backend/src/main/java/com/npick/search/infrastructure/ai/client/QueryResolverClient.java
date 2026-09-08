package com.npick.search.infrastructure.ai.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.npick.search.infrastructure.ai.client.request.QueryResolutionApiRequest;
import com.npick.search.infrastructure.ai.client.response.QueryResolutionApiResponse;

/**
 * 질의 리졸버 HTTP 클라이언트.
 *
 * <p>base-url 과 timeout 은 {@code spring.http.serviceclient.queryResolver.*} 가 소유한다. 재시도 설정은 두지 않는다 — 실패하면 그대로 올라온다 (FRD
 * v3.1 §6.2 "동기 AI 재시도 없음").
 *
 * <p>TODO(S15P21A501-45): 리졸버 모듈은 dev 에 있으나 {@code ai/src/npick_worker/app.py} 에 엔드포인트가 없다. 아래 경로와 본문 형태는 그 계약이 정해지기 전의
 * 잠정값이다.
 */
@HttpExchange
public interface QueryResolverClient {

    @PostExchange("/resolve")
    QueryResolutionApiResponse resolve(@RequestBody QueryResolutionApiRequest request);
}
