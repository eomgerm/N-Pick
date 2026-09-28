package com.npick.search.application.query.execution;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

public record SearchExecutionDetail(
        long searchExecutionId,
        String executionType,
        Long replayOfFeedbackId,
        String status,
        String queryText,
        String normalizedQuery,
        JsonNode explicitFilters,
        JsonNode normalizedFilters,
        String queryFingerprint,
        String normalizationVersion,
        JsonNode degradedReasons,
        String errorCode,
        String parseSource,
        JsonNode resolverOutput,
        JsonNode parsedQuery,
        JsonNode appliedRules,
        Integer parseMs,
        JsonNode candidates,
        JsonNode filtered,
        JsonNode appliedExcludes,
        JsonNode searchConfig,
        String configVersion,
        Integer executionMs,
        JsonNode verificationContext,
        List<Result> results,
        Instant createdAt,
        Instant updatedAt) {

    public record Result(long searchResultId, long sceneId, int rank, JsonNode explain) {}
}
