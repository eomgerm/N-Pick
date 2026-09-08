package com.npick.search.infrastructure.ai.client.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.search.application.port.QueryResolution;

/**
 * 질의 리졸버 응답 본문. FRD v2.2 §6.2 의 JSON 계약을 그대로 받는다.
 *
 * <p>이 DTO 가 리졸버의 snake_case 계약을 떠안아 application 의 {@link QueryResolution} 이 외부 표현에 묶이지 않게 한다.
 *
 * <p>TODO(S15P21A501-45): 02-container 요소 표에 따르면 리졸버는 질의 임베딩과 Kiwi 형태소 토큰도 함께 반환한다. 해당 API 스펙이 확정되면 여기에 필드를 추가하고 별도
 * Port 결과로 내보낸다.
 */
public record QueryResolutionApiResponse(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("intent") String intent,
        @JsonProperty("date_windows") List<DateWindow> dateWindows,
        @JsonProperty("incident_names") List<IncidentName> incidentNames,
        @JsonProperty("entities") List<Entity> entities,
        @JsonProperty("locations") List<Location> locations,
        @JsonProperty("expanded_terms") List<String> expandedTerms,
        @JsonProperty("confidence") double confidence) {

    public QueryResolution toResolution() {
        return new QueryResolution(
                schemaVersion,
                enumOf(QueryResolution.Intent.class, intent),
                map(dateWindows, DateWindow::toDateWindow),
                map(incidentNames, IncidentName::toIncidentName),
                map(entities, Entity::toEntity),
                map(locations, Location::toLocation),
                expandedTerms == null ? List.of() : List.copyOf(expandedTerms),
                confidence);
    }

    public record QuerySpan(
            @JsonProperty("start") int start,
            @JsonProperty("end") int end) {

        QueryResolution.QuerySpan toQuerySpan() {
            return new QueryResolution.QuerySpan(start, end);
        }
    }

    public record DateWindow(
            @JsonProperty("field") String field,
            @JsonProperty("start") LocalDate start,
            @JsonProperty("end_exclusive") LocalDate endExclusive,
            @JsonProperty("origin") String origin,
            @JsonProperty("query_span") QuerySpan querySpan,
            @JsonProperty("confidence") double confidence) {

        QueryResolution.DateWindow toDateWindow() {
            return new QueryResolution.DateWindow(
                    enumOf(QueryResolution.DateField.class, field),
                    start,
                    endExclusive,
                    enumOf(QueryResolution.Origin.class, origin),
                    toSpan(querySpan),
                    confidence);
        }
    }

    public record IncidentName(
            @JsonProperty("value") String value,
            @JsonProperty("origin") String origin,
            @JsonProperty("query_span") QuerySpan querySpan,
            @JsonProperty("confidence") double confidence) {

        QueryResolution.IncidentName toIncidentName() {
            return new QueryResolution.IncidentName(
                    value, enumOf(QueryResolution.Origin.class, origin), toSpan(querySpan), confidence);
        }
    }

    public record Entity(
            @JsonProperty("type") String type,
            @JsonProperty("value") String value,
            @JsonProperty("origin") String origin,
            @JsonProperty("query_span") QuerySpan querySpan,
            @JsonProperty("confidence") double confidence) {

        QueryResolution.Entity toEntity() {
            return new QueryResolution.Entity(
                    enumOf(QueryResolution.EntityType.class, type),
                    value,
                    enumOf(QueryResolution.Origin.class, origin),
                    toSpan(querySpan),
                    confidence);
        }
    }

    public record Location(
            @JsonProperty("type") String type,
            @JsonProperty("value") String value,
            @JsonProperty("origin") String origin,
            @JsonProperty("query_span") QuerySpan querySpan,
            @JsonProperty("confidence") double confidence) {

        QueryResolution.Location toLocation() {
            return new QueryResolution.Location(
                    enumOf(QueryResolution.LocationType.class, type),
                    value,
                    enumOf(QueryResolution.Origin.class, origin),
                    toSpan(querySpan),
                    confidence);
        }
    }

    private static QueryResolution.QuerySpan toSpan(QuerySpan span) {
        return span == null ? null : span.toQuerySpan();
    }

    private static <S, T> List<T> map(List<S> source, Function<S, T> mapper) {
        return source == null ? List.of() : source.stream().map(mapper).toList();
    }

    /**
     * 리졸버가 보낸 값이 우리가 아는 enum 이 아니면 schema 불일치다. 실패를 {@code RESOLVER_SCHEMA_INVALID} 로 분류할 수 있도록
     * IllegalArgumentException 을 그대로 던진다.
     */
    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }
}
