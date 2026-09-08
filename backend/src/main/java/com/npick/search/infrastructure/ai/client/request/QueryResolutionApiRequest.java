package com.npick.search.infrastructure.ai.client.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.search.application.port.QueryResolutionRequest;

/**
 * 질의 리졸버 요청 본문.
 *
 * <p>TODO(S15P21A501-45): 리졸버 API 스펙이 확정되면 필드명을 맞춘다. 지금은 FRD v2.2 §6.1 의 canonical scope 를 그대로 보낸다.
 */
public record QueryResolutionApiRequest(
        @JsonProperty("canonical_query") String canonicalQuery,
        @JsonProperty("canonical_filters") String canonicalFilters) {

    public static QueryResolutionApiRequest from(QueryResolutionRequest request) {
        return new QueryResolutionApiRequest(request.canonicalQuery(), request.canonicalFiltersJson());
    }
}
