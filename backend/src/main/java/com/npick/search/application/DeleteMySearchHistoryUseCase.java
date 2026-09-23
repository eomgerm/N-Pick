package com.npick.search.application;

/** 「내 검색 기록」에서 기록을 지운다 (S15P21A501-276). 저장은 보존하고 이 화면에서만 감춘다. */
public interface DeleteMySearchHistoryUseCase {

    void deleteMine(long searchExecutionId, long ownerId);

    /** 본인의 가시 기록을 한 번에 모두 지운다 (S15P21A501-291). 지울 것이 없어도 오류가 아니다. */
    void deleteAllMine(long ownerId);
}
