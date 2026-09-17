package com.npick.search.application.port;

import java.util.List;

import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.ExplicitDateFilters;
import com.npick.search.domain.model.NormalizedSearch;
import com.npick.search.domain.model.QueryResolution;

/** 리졸버가 정규화 결과를 만든 직후 한 번 저장하는 실행 시작 스냅샷. */
public record StartSearchExecution(
        long searchedById,
        ExecutionType executionType,
        Long replayOfFeedbackId,
        String rawQuery,
        ExplicitDateFilters explicitFilters,
        NormalizedSearch normalizedSearch,
        ResolverOutput resolverOutput,
        List<AnchorFinding> findings,
        ParseSource parseSource,
        Integer parseMs,
        List<SearchDegradedReason> degradedReasons) {

    public StartSearchExecution {
        if (searchedById <= 0 || rawQuery == null || rawQuery.isBlank()) {
            throw new IllegalArgumentException("검색 실행자와 원문 질의는 필수다");
        }
        if (executionType == null || explicitFilters == null || normalizedSearch == null || parseSource == null) {
            throw new IllegalArgumentException("검색 시작 스냅샷의 필수 값이 없다");
        }
        if ((executionType == ExecutionType.REPLAY) != (replayOfFeedbackId != null)) {
            throw new IllegalArgumentException("replay 실행과 원본 feedback은 함께 있어야 한다");
        }
        if (replayOfFeedbackId != null && replayOfFeedbackId <= 0) {
            throw new IllegalArgumentException("replay 원본 feedback ID는 양수여야 한다");
        }
        if (parseMs != null && parseMs < 0) {
            throw new IllegalArgumentException("parseMs는 음수일 수 없다");
        }
        findings = findings == null ? List.of() : List.copyOf(findings);
        degradedReasons = degradedReasons == null ? List.of() : List.copyOf(degradedReasons);
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

    /** raw는 교정 전, verified는 anchor 검증 후 해석이다. fallback이면 이 값 전체가 null이다. */
    public record ResolverOutput(
            QueryResolution rawResolution,
            QueryResolution verifiedResolution,
            String resolutionSchemaVersion,
            String promptVersion,
            String modelVersion) {}
}
