package com.npick.search.presentation.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.search.application.query.search.SearchExecutionResult;

/**
 * {@code POST /search} 응답의 {@code data} (web-api §5).
 *
 * <p><b>ID 는 전부 문자열이다.</b> TSID 가 2^53 을 넘으면 JavaScript 의 number 로 받는 순간 값이 뭉개진다. 저장되지 않은 결과는 {@code null} 이고, 그 자리를 빈
 * 문자열이나 {@code "0"} 으로 채우지 않는다 — 계약이 «이 결과로 문의할 수 없다»를 null 로 표현한다 (§5.1).
 *
 * <p>{@code null} 을 지우지 않는다. 키가 사라지면 화면이 「값이 없다」와 「필드가 없다」를 구분하지 못한다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SearchResponse(
        @JsonProperty("search_execution_id") String searchExecutionId,
        @JsonProperty("status") String status,
        @JsonProperty("degraded_reasons") List<String> degradedReasons,
        @JsonProperty("query_resolution_status") String queryResolutionStatus,
        @JsonProperty("has_applied_review_rule") boolean hasAppliedReviewRule,
        @JsonProperty("guard_summary") GuardSummary guardSummary,
        @JsonProperty("shortage_reasons") List<String> shortageReasons,
        @JsonProperty("results") List<Result> results) {

    public static SearchResponse of(SearchExecutionResult result) {
        return new SearchResponse(
                id(result.executionId()),
                result.status(),
                result.degradedReasons(),
                result.resolved() ? "resolved" : "fallback",
                result.hasAppliedReviewRule(),
                new GuardSummary(
                        result.guardSummary().excludedResultCount(),
                        result.guardSummary().reasons()),
                result.shortageReasons(),
                result.results().stream().map(Result::of).toList());
    }

    private static String id(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    public record GuardSummary(
            @JsonProperty("excluded_result_count") int excludedResultCount,
            @JsonProperty("reasons") List<String> reasons) {}

    public record Result(
            @JsonProperty("search_result_id") String searchResultId,
            @JsonProperty("scene_id") String sceneId,
            @JsonProperty("clip_id") String clipId,
            @JsonProperty("rank") int rank,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("scene_description") String sceneDescription,
            @JsonProperty("start_time_ms") long startTimeMs,
            @JsonProperty("end_time_ms") long endTimeMs,
            @JsonProperty("broadcast_date") DateValue broadcastDate,
            @JsonProperty("filmed_date") DateValue filmedDate,
            @JsonProperty("shot_type") String shotType,
            @JsonProperty("scene_type") String sceneType,
            @JsonProperty("matched_keywords") List<String> matchedKeywords,
            @JsonProperty("match_evidence") List<MatchEvidence> matchEvidence) {

        static Result of(SearchExecutionResult.ResultCard card) {
            return new Result(
                    id(card.searchResultId()),
                    String.valueOf(card.sceneId()),
                    String.valueOf(card.clipId()),
                    card.rank(),
                    card.displayName(),
                    card.sceneDescription(),
                    card.startTimeMs(),
                    card.endTimeMs(),
                    DateValue.of(card.broadcastDate()),
                    DateValue.of(card.filmedDate()),
                    card.shotType(),
                    card.sceneType(),
                    card.matchedKeywords(),
                    card.matchEvidence().stream().map(MatchEvidence::of).toList());
        }
    }

    public record DateValue(
            @JsonProperty("value") String value,
            @JsonProperty("verification_status") String verificationStatus) {

        static DateValue of(SearchExecutionResult.DateValue value) {
            return new DateValue(value.value() == null ? null : value.value().toString(), value.verificationStatus());
        }
    }

    public record MatchEvidence(
            @JsonProperty("field") String field,
            @JsonProperty("value") String value,
            @JsonProperty("source") String source,
            @JsonProperty("verification_status") String verificationStatus) {

        static MatchEvidence of(SearchExecutionResult.MatchEvidence evidence) {
            return new MatchEvidence(
                    evidence.field(), evidence.value(), evidence.source(), evidence.verificationStatus());
        }
    }
}
