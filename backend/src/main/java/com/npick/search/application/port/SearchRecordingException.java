package com.npick.search.application.port;

/** 검색 실행 기록 저장 실패. 호출부는 start 실패와 complete 실패를 서로 다른 정책으로 처리한다. */
public final class SearchRecordingException extends RuntimeException {
    public SearchRecordingException(String message, Throwable cause) {
        super(message, cause);
    }

    public SearchRecordingException(String message) {
        super(message);
    }
}
