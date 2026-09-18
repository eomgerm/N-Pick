package com.npick.search.application.query;

import java.util.List;

/**
 * 검색 실행 1건과 그 실행이 남긴 결과 전량 (S15P21A501-198).
 *
 * <p>결과를 전량 싣는 이유는 스냅샷 복원 가능 판정이 <b>모든</b> 행의 {@code explain_json} 을 봐야 하기 때문이다.
 * 목록이 rank=1 만 읽고 상세가 전량을 읽으면 같은 기록의 판정이 갈린다 — 계약이 금지한다(S15P21A501-185).
 */
public record SearchHistoryRecord(SearchHistoryItem item, List<SearchHistoryResultRow> results) {}
