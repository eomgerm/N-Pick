package com.npick.search.presentation.response;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import com.npick.search.application.query.execution.SearchExecutionDetail;

public record SearchExecutionDetailResponse(
        @JsonProperty("search_execution_id") String searchExecutionId,
        @JsonProperty("execution_type") String executionType,
        @JsonProperty("replay_of_feedback_id") String replayOfFeedbackId,
        String status,
        @JsonProperty("query_text") String queryText,
        @JsonProperty("normalized_query") String normalizedQuery,
        @JsonProperty("explicit_filters") JsonNode explicitFilters,
        @JsonProperty("normalized_filters") JsonNode normalizedFilters,
        @JsonProperty("query_fingerprint") String queryFingerprint,
        @JsonProperty("normalization_version") String normalizationVersion,
        @JsonProperty("degraded_reasons") JsonNode degradedReasons,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("parse_source") String parseSource,
        @JsonProperty("resolver_output") JsonNode resolverOutput,
        @JsonProperty("parsed_query") JsonNode parsedQuery,
        @JsonProperty("applied_rules") JsonNode appliedRules,
        @JsonProperty("parse_ms") Integer parseMs,
        JsonNode candidates,
        JsonNode filtered,
        @JsonProperty("applied_excludes") JsonNode appliedExcludes,
        @JsonProperty("search_config") JsonNode searchConfig,
        @JsonProperty("config_version") String configVersion,
        @JsonProperty("execution_ms") Integer executionMs,
        @JsonProperty("verification_context") JsonNode verificationContext,
        List<Result> results,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {

    public static SearchExecutionDetailResponse from(SearchExecutionDetail detail) {
        return new SearchExecutionDetailResponse(
                Long.toString(detail.searchExecutionId()),
                detail.executionType(),
                detail.replayOfFeedbackId() == null ? null : Long.toString(detail.replayOfFeedbackId()),
                detail.status(),
                detail.queryText(),
                detail.normalizedQuery(),
                detail.explicitFilters(),
                detail.normalizedFilters(),
                detail.queryFingerprint(),
                detail.normalizationVersion(),
                detail.degradedReasons(),
                detail.errorCode(),
                detail.parseSource(),
                detail.resolverOutput(),
                detail.parsedQuery(),
                detail.appliedRules(),
                detail.parseMs(),
                detail.candidates(),
                detail.filtered(),
                detail.appliedExcludes(),
                detail.searchConfig(),
                detail.configVersion(),
                detail.executionMs(),
                detail.verificationContext(),
                detail.results().stream().map(Result::from).toList(),
                detail.createdAt(),
                detail.updatedAt());
    }

    public record Result(
            @JsonProperty("search_result_id") String searchResultId,
            @JsonProperty("scene_id") String sceneId,
            int rank,
            JsonNode explain) {
        private static Result from(SearchExecutionDetail.Result result) {
            return new Result(
                    Long.toString(result.searchResultId()),
                    Long.toString(result.sceneId()),
                    result.rank(),
                    result.explain());
        }
    }
}
