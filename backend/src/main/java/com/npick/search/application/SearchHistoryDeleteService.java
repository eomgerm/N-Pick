package com.npick.search.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.search.application.port.SearchHistorySoftDeletePort;
import com.npick.search.domain.error.SearchErrorCode;

/**
 * 「내 검색 기록」 삭제 서비스 (S15P21A501-276).
 *
 * <p>조회({@link SearchHistoryQueryService})와 나눠 둔다. 이쪽만 쓰기 트랜잭션이다.
 */
@Service
public class SearchHistoryDeleteService implements DeleteMySearchHistoryUseCase {

    private final SearchHistorySoftDeletePort softDeletePort;

    public SearchHistoryDeleteService(SearchHistorySoftDeletePort softDeletePort) {
        this.softDeletePort = softDeletePort;
    }

    /** 타인 소유·미존재·대상 밖(replay 등)을 구분하지 않고 같은 오류로 던진다 — 상세 조회와 같은 이유로 존재 여부를 노출하지 않는다. 이미 숨긴 기록은 오류가 아니다(포트 계약). */
    @Override
    @Transactional
    public void deleteMine(long searchExecutionId, long ownerId) {
        if (!softDeletePort.hideOwned(searchExecutionId, ownerId)) {
            throw new BusinessException(SearchErrorCode.SEARCH_EXECUTION_NOT_FOUND);
        }
    }

    /** 지운 기록 수와 무관하게 성공이다 — 빈 목록을 비우는 것은 오류가 아니라 멱등한 요청이다(S15P21A501-291). */
    @Override
    @Transactional
    public void deleteAllMine(long ownerId) {
        softDeletePort.hideAllOwned(ownerId);
    }
}
