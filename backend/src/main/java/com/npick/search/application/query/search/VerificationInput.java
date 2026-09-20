package com.npick.search.application.query.search;

/** 원 신고에서 자동으로 가져온 검증 재검색 입력 (F-12 2). */
public record VerificationInput(String rawQuery, ExecuteSearchQuery.DateFilters dateFilters) {}
