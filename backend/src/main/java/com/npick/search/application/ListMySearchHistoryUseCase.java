package com.npick.search.application;

import com.npick.search.application.query.SearchHistoryListPage;

/** 검색 화면 사이드바용 「내 검색 기록」 목록 (S15P21A501-198). 사용자 ID 는 세션에서만 온다. */
public interface ListMySearchHistoryUseCase {

    SearchHistoryListPage listMine(long ownerId, int page, int size);
}
