package com.npick.search.application.query;

import java.util.List;

/** 「내 검색 기록」목록 한 페이지와 소유자 전체 건수 (S15P21A501-198). */
public record SearchHistoryListPage(List<SearchHistoryRecord> records, long totalElements) {}
