package com.npick.search.application.port;

import java.util.List;

import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.ExplicitDateFilters;
import com.npick.search.domain.model.NormalizedSearch;
import com.npick.search.domain.model.QueryResolution;

/** 리졸버 응답을 받은 직후 running 실행에 덧붙이는 해석 스냅샷. */
public record RecordSearchExecutionResolution(
        long searchExecutionId,
        ExplicitDateFilters explicitFilters,
        NormalizedSearch normalizedSearch,
        ResolverOutput resolverOutput,
        List<AnchorFinding> findings,
        StartSearchExecution.ParseSource parseSource,
        Integer parseMs,
        List<SearchDegradedReason> degradedReasons) {

    public RecordSearchExecutionResolution {
        if (searchExecutionId <= 0
                || explicitFilters == null
                || normalizedSearch == null
                || parseSource == null) {
            throw new IllegalArgumentException("검색 해석 스냅샷의 필수 값이 없다");
        }
        if (parseMs != null && parseMs < 0) {
            throw new IllegalArgumentException("parseMs는 음수일 수 없다");
        }
        findings = findings == null ? List.of() : List.copyOf(findings);
        degradedReasons = degradedReasons == null ? List.of() : List.copyOf(degradedReasons);
    }

    /** raw는 교정 전, verified는 anchor 검증 후 해석이다. fallback이면 이 값 전체가 null이다. */
    public record ResolverOutput(
            QueryResolution rawResolution,
            QueryResolution verifiedResolution,
            String resolutionSchemaVersion,
            String promptVersion,
            String modelVersion) {}
}
