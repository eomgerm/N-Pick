package com.npick.search.application.port;

/**
 * Resolver 호출 입력. FRD v2.2 §6.1 의 canonical scope 를 담는다.
 *
 * <p>정규화는 호출측 소관이다. 여기에는 이미 정규화된 값만 들어온다. session·turn·fingerprint 는 resolver 입력으로 넘기지 않는다 (FR-QRY-005).
 *
 * @param canonicalQuery 정규화된 검색어
 * @param canonicalFiltersJson 정규화된 explicit filter 의 canonical JSON. 없으면 {@code null}
 */
public record QueryResolutionRequest(String canonicalQuery, String canonicalFiltersJson) {

    public static QueryResolutionRequest of(String canonicalQuery) {
        return new QueryResolutionRequest(canonicalQuery, null);
    }
}
