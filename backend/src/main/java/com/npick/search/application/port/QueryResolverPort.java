package com.npick.search.application.port;

/**
 * 질의 리졸버 호출 계약.
 *
 * <p>구현을 갈아끼우는 지점이다. EC2 의 local 모델이든 승인된 GMS 든 이 인터페이스 뒤에서 교체한다 ({@code docs/architecture/02-container.md}).
 *
 * <p>호출 조건 판단과 실패 시 fallback 실행은 이 Port 의 책임이 아니라 검색 오케스트레이션의 책임이다 (FRD v3.1 F-05, §6.2).
 */
public interface QueryResolverPort {

    /**
     * 리졸버를 동기 호출한다. 재시도하지 않는다 (FRD v3.1 §6.2 "동기 AI 재시도 없음").
     *
     * @param rawQuery <b>사용자가 친 원문</b>. 정규화 질의를 넣으면 안 된다 — {@code query_span} 이 원문 기준이라 정규화된 문자열을 넣으면 explicit anchor 가
     *     전부 강등된다 (F-05)
     * @throws com.npick.common.error.BusinessException 호출이 실패한 경우. {@code errorCode()} 로 timeout·schema·rate
     *     limit·network 를 구분해 원 검색어 BM25 로 전환할지 판단한다 (§6.2)
     */
    QueryResolutionResult resolve(String rawQuery);
}
