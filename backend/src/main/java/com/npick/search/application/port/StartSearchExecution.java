package com.npick.search.application.port;

/**
 * 리졸버 호출 전에 남기는 최소 실행 시작 스냅샷.
 *
 * <p>원문을 AI에 보내기 전에 이 행이 먼저 커밋되어야 한다. 리졸버 산출물은 {@link RecordSearchExecutionResolution}으로 이어서 기록한다.
 */
public record StartSearchExecution(
        long searchedById, ExecutionType executionType, Long replayOfFeedbackId, String rawQuery) {

    public StartSearchExecution {
        if (searchedById <= 0 || rawQuery == null || rawQuery.isBlank()) {
            throw new IllegalArgumentException("검색 실행자와 원문 질의는 필수다");
        }
        if (executionType == null) {
            throw new IllegalArgumentException("검색 시작 스냅샷의 필수 값이 없다");
        }
        if ((executionType == ExecutionType.REPLAY) != (replayOfFeedbackId != null)) {
            throw new IllegalArgumentException("replay 실행과 원본 feedback은 함께 있어야 한다");
        }
        if (replayOfFeedbackId != null && replayOfFeedbackId <= 0) {
            throw new IllegalArgumentException("replay 원본 feedback ID는 양수여야 한다");
        }
    }

    public enum ExecutionType {
        NORMAL("original"),
        REPLAY("replay");

        private final String databaseValue;

        ExecutionType(String databaseValue) {
            this.databaseValue = databaseValue;
        }

        public String databaseValue() {
            return databaseValue;
        }
    }

    public enum ParseSource {
        RESOLVER("resolver"),
        FALLBACK("fallback");

        private final String databaseValue;

        ParseSource(String databaseValue) {
            this.databaseValue = databaseValue;
        }

        public String databaseValue() {
            return databaseValue;
        }
    }
}
