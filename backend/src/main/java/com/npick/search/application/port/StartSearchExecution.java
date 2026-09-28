package com.npick.search.application.port;

/**
 * 리졸버 호출 전에 남기는 최소 실행 시작 스냅샷.
 *
 * <p>원문을 AI에 보내기 전에 이 행이 먼저 커밋되어야 한다. 리졸버 산출물은 {@link RecordSearchExecutionResolution}으로 이어서 기록한다.
 */
public record StartSearchExecution(
        long searchedById,
        ExecutionType executionType,
        Long replayOfFeedbackId,
        String rawQuery,
        Long parentExecutionId) {

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
        // parentExecutionId 는 더보기(offset>0) 이어보기가 첫 페이지(root) 실행을 가리키는 값이다.
        // root 검색은 null 이다. 결과 재사용이 아니라 기록 그룹핑 힌트다 (S15P21A501-280).
        if (parentExecutionId != null && parentExecutionId <= 0) {
            throw new IllegalArgumentException("parent 실행 ID는 양수여야 한다");
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
