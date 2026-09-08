package com.npick.clip.application.command.store;

/** 이번 저장 작업이 소유한 원본. 등록 실패 시에만 discard를 호출한다. */
public interface StoreVideoResult {
    String storageKey();

    void discard();
}
