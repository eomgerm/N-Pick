package com.npick.search.application;

import com.npick.search.application.query.SearchHistoryRecord;

/** 검색 화면 사이드바용 「내 검색 기록」 상세 (S15P21A501-198). */
public interface GetMySearchHistoryDetailUseCase {

    SearchHistoryRecord detailMine(long searchExecutionId, long ownerId);
}
