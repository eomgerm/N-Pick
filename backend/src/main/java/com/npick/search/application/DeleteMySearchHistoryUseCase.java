package com.npick.search.application;

/** 「내 검색 기록」에서 기록 하나를 지운다 (S15P21A501-276). 저장은 보존하고 이 화면에서만 감춘다. */
public interface DeleteMySearchHistoryUseCase {

    void deleteMine(long searchExecutionId, long ownerId);
}
