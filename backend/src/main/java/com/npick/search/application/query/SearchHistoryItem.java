package com.npick.search.application.query;

import java.time.Instant;

/**
 * 「내 검색 기록」한 실행의 헤더 (S15P21A501-198). 목록·상세가 같은 값을 쓴다.
 *
 * <p>JSONB 컬럼은 파싱하지 않고 원문 문자열로 싣는다. 조회층이 Jackson 을 알 필요가 없고, 응답 조립 지점 ({@code SearchSnapshot}) 한 곳에서만 해석하면 목록·상세가 같은
 * 판정을 내는 것이 구조로 보장된다.
 */
public record SearchHistoryItem(
        long searchExecutionId,
        String queryText,
        String explicitFiltersJson,
        Instant createdAt,
        String status,
        String degradedReasonsJson,
        String parseSource,
        String appliedRulesJson,
        String appliedExcludesJson,
        String filteredJson) {}
