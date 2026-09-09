package com.npick.search.application.port;

/**
 * 질의 리졸버 호출 계약.
 *
 * <p>구현을 갈아끼우는 지점이다. EC2 의 local 모델이든 외부 제공자든 이 인터페이스 뒤에서 교체한다 ({@code docs/architecture/02-container.md}). <b>어느 제공자를
 * 쓸지의 승인 판정과 전송 허용 목록은 이 Port 가 강제하지 않는다</b> — FRD v3.1 §6.4 의 그 장치는 {@code S15P21A501-134} 의 몫이다. 여기서는 설정이 고른 대상을 부를
 * 뿐이다.
 *
 * <p>호출 조건 판단과 실패 시 fallback 실행은 이 Port 의 책임이 아니라 검색 오케스트레이션의 책임이다 (FRD v3.1 F-05, §6.2).
 *
 * <h2>실패가 오는 길이 둘이다</h2>
 *
 * 나누는 기준은 실패의 종류가 아니라 <b>정규화 결과가 손에 남아 있는가</b> 이다. §6.2 의 원 검색어 BM25 fallback 은
 * {@link QueryNormalization#searchTokens()} 없이는 성립하지 않기 때문이다.
 *
 * <ul>
 *   <li><b>정상 반환 + {@link QueryResolutionResult#failure()}</b> — 리졸버가 응답은 준 경우다. 해석만 실패했고 정규화는 살아 있으므로 그 토큰으로 BM25 를
 *       이어간다. 리졸버 장애 대부분이 이 길로 온다.
 *   <li><b>{@code BusinessException}</b> — 리졸버에 <b>닿지 못했거나</b> 응답에서 정규화조차 읽지 못한 경우다. 재료가 없어 fallback 이 불가능하므로 검색 실패로
 *       처리한다. 결과 0건으로 위장하지 않는다 (F-06 완료 기준).
 * </ul>
 */
public interface QueryResolverPort {

    /**
     * 리졸버를 동기 호출한다. 재시도하지 않는다 (FRD v3.1 §6.2 "동기 AI 재시도 없음").
     *
     * @param rawQuery <b>사용자가 친 원문</b>. 정규화 질의를 넣으면 안 된다 — {@code query_span} 이 원문 기준이라 정규화된 문자열을 넣으면 explicit anchor 가
     *     전부 강등된다 (F-05)
     * @return 해석 성공 여부와 무관하게 정규화는 채워져 있다. 해석 실패는 {@link QueryResolutionResult#failure()} 로 온다
     * @throws com.npick.common.error.BusinessException 리졸버에 닿지 못했거나 정규화를 읽지 못한 경우. {@code errorCode()} 는
     *     {@code RESOLVER_TIMEOUT} · {@code RESOLVER_NETWORK} · {@code RESOLVER_SCHEMA_INVALID} ·
     *     {@code RESOLVER_RATE_LIMITED} · {@code RESOLVER_FAILED} 중 하나이며, 리졸버가 400 으로 답한 경우에 한해
     *     {@code QUERY_NOT_NORMALIZABLE} 이다 — 이것만 의존성 장애가 아니라 사용자 입력 문제다
     */
    QueryResolutionResult resolve(String rawQuery);
}
