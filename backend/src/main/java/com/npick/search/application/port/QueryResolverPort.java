package com.npick.search.application.port;

/**
 * LLM Query Resolver 호출 계약.
 *
 * <p>구현을 갈아끼우는 지점이다. local 모델이든 승인된 GMS 든 이 인터페이스 뒤에서 교체한다.
 *
 * <p>호출 조건 판단(resolution patch 가 없을 때만 호출, FR-QRY-020)은 이 Port 의 책임이 아니라 호출측 유스케이스의 책임이다.
 */
public interface QueryResolverPort {

    /**
     * Resolver 를 동기 호출한다. 재시도하지 않는다 (FR-QRY-023).
     *
     * @throws com.npick.search.application.error.SearchException 호출이 실패한 경우. {@code errorCode()} 로 timeout·schema·rate
     *     limit·network 를 구분해 fallback 을 판단한다 (FR-QRY-022)
     */
    QueryResolution resolve(QueryResolutionRequest request);
}
