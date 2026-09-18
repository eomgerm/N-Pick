package com.npick.search.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.search.application.query.SearchHistoryDetailQuery;
import com.npick.search.application.query.SearchHistoryListPage;
import com.npick.search.application.query.SearchHistoryListQuery;
import com.npick.search.application.query.SearchHistoryRecord;
import com.npick.search.domain.error.SearchErrorCode;

/**
 * 「내 검색 기록」 조회 서비스 (S15P21A501-198). 본인이 실행한 검색만 읽는 읽기 전용 경로다.
 */
@Service
public class SearchHistoryQueryService implements ListMySearchHistoryUseCase, GetMySearchHistoryDetailUseCase {

    private final SearchHistoryListQuery listQuery;
    private final SearchHistoryDetailQuery detailQuery;

    public SearchHistoryQueryService(SearchHistoryListQuery listQuery, SearchHistoryDetailQuery detailQuery) {
        this.listQuery = listQuery;
        this.detailQuery = detailQuery;
    }

    @Override
    @Transactional(readOnly = true)
    public SearchHistoryListPage listMine(long ownerId, int page, int size) {
        return new SearchHistoryListPage(listQuery.findByOwner(ownerId, page, size), listQuery.countByOwner(ownerId));
    }

    /** 타인 소유·미존재·대상 밖(replay 등)을 구분하지 않고 같은 오류로 던진다(존재 여부 노출 금지). */
    @Override
    @Transactional(readOnly = true)
    public SearchHistoryRecord detailMine(long searchExecutionId, long ownerId) {
        return detailQuery
                .findByOwner(searchExecutionId, ownerId)
                .orElseThrow(() -> new BusinessException(SearchErrorCode.SEARCH_EXECUTION_NOT_FOUND));
    }
}
