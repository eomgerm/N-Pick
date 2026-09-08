package com.npick.search.infrastructure.ai.client.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 질의 리졸버 요청 본문.
 *
 * <p>리졸버 모듈의 {@code resolve_query} 는 원문 질의 하나만 받는다. 사용자가 고른 명시 필터는 리졸버에 넘기지 않는다 — FRD v3.1 F-05 "사용자가 직접 입력한 필터는 AI나
 * 규칙이 바꿀 수 없다".
 *
 * <p>TODO(S15P21A501-45): 리졸버의 HTTP 엔드포인트가 아직 없다. 키 이름은 엔드포인트 계약이 정해지면 맞춘다.
 */
public record QueryResolutionApiRequest(
        @JsonProperty("query") String query) {}
