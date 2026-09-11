package com.npick.clip.domain.model;

/** 검색 준비와 활성 처리 번호를 분리해 이전 실행의 늦은 완료를 차단한다. */
public record ClipPublication(boolean deleted, Integer activeProcessingNo) {
    public boolean canActivate(int processingNo, boolean mediaAvailable, boolean resultsReady) {
        return !deleted
                && mediaAvailable
                && resultsReady
                && (activeProcessingNo == null || processingNo >= activeProcessingNo);
    }
}
