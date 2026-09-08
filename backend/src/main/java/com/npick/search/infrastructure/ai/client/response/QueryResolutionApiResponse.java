package com.npick.search.infrastructure.ai.client.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.search.application.port.AnchorFinding;
import com.npick.search.application.port.QueryResolution;
import com.npick.search.application.port.QueryResolutionResult;

/**
 * 질의 리졸버 응답 본문. 리졸버 모듈의 {@code ResolutionResult} 계약을 그대로 받는다.
 *
 * <p>이 DTO 가 리졸버의 snake_case 표기를 떠안아 application 의 {@link QueryResolution} 이 외부 표현에 묶이지 않게 한다 (설계 정본 §10).
 *
 * <p>없는 필드를 조용히 기본값으로 채우지 않는다. 키 이름이 어긋나면 오류 없이 빈 해석이 성공으로 흘러가고, 그러면 검색은 AI 해석을 받은 것처럼 보이면서 아무 조건도 걸리지 않는다.
 *
 * <p>TODO(S15P21A501-45): HTTP 엔드포인트가 아직 없어 본문 형태는 모듈 반환 타입을 기준으로 둔 잠정값이다. 엔드포인트 계약이 정해지면 맞춘다.
 */
public record QueryResolutionApiResponse(
        @JsonProperty("resolution") Resolution resolution,
        @JsonProperty("findings") List<Finding> findings,
        @JsonProperty("resolution_schema_version") String resolutionSchemaVersion,
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("model_version") String modelVersion) {

    public QueryResolutionResult toResult() {
        require(resolution != null, "resolution");
        require(resolutionSchemaVersion != null, "resolution_schema_version");
        require(promptVersion != null, "prompt_version");
        require(modelVersion != null, "model_version");

        return new QueryResolutionResult(
                resolution.toResolution(),
                map(findings, Finding::toFinding),
                resolutionSchemaVersion,
                promptVersion,
                modelVersion);
    }

    public record Resolution(
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("intent") String intent,
            @JsonProperty("date_windows") List<DateWindow> dateWindows,
            @JsonProperty("incident_names") List<IncidentName> incidentNames,
            @JsonProperty("entities") List<Entity> entities,
            @JsonProperty("locations") List<Location> locations,
            @JsonProperty("classifications") List<Classification> classifications,
            @JsonProperty("expanded_terms") List<String> expandedTerms,
            @JsonProperty("confidence") Double confidence) {

        QueryResolution toResolution() {
            require(schemaVersion != null, "resolution.schema_version");
            return new QueryResolution(
                    schemaVersion,
                    enumOf(QueryResolution.Intent.class, intent, "resolution.intent"),
                    map(dateWindows, DateWindow::toDateWindow),
                    map(incidentNames, IncidentName::toIncidentName),
                    map(entities, Entity::toEntity),
                    map(locations, Location::toLocation),
                    map(classifications, Classification::toClassification),
                    expandedTerms == null ? List.of() : List.copyOf(expandedTerms),
                    toConfidence(confidence, "resolution"));
        }
    }

    public record Finding(
            @JsonProperty("path") String path,
            @JsonProperty("action") String action,
            @JsonProperty("reason") String reason) {

        AnchorFinding toFinding() {
            return new AnchorFinding(path, action, reason);
        }
    }

    public record QuerySpan(
            @JsonProperty("start") Integer start,
            @JsonProperty("end") Integer end) {

        QueryResolution.QuerySpan toQuerySpan(String path) {
            require(start != null && end != null, path + ".query_span");
            return new QueryResolution.QuerySpan(start, end);
        }
    }

    public record DateWindow(
            @JsonProperty("field") String field,
            @JsonProperty("start") LocalDate start,
            @JsonProperty("end_exclusive") LocalDate endExclusive,
            @JsonProperty("origin") String origin,
            @JsonProperty("query_span") QuerySpan querySpan,
            @JsonProperty("confidence") Double confidence) {

        QueryResolution.DateWindow toDateWindow() {
            require(start != null && endExclusive != null, "date_windows.start/end_exclusive");
            return new QueryResolution.DateWindow(
                    enumOf(QueryResolution.DateField.class, field, "date_windows.field"),
                    start,
                    endExclusive,
                    toOrigin(origin, "date_windows"),
                    toSpan(querySpan, "date_windows"),
                    toConfidence(confidence, "date_windows"));
        }
    }

    public record IncidentName(
            @JsonProperty("value") String value,
            @JsonProperty("origin") String origin,
            @JsonProperty("query_span") QuerySpan querySpan,
            @JsonProperty("confidence") Double confidence) {

        QueryResolution.IncidentName toIncidentName() {
            require(value != null, "incident_names.value");
            return new QueryResolution.IncidentName(
                    value,
                    toOrigin(origin, "incident_names"),
                    toSpan(querySpan, "incident_names"),
                    toConfidence(confidence, "incident_names"));
        }
    }

    public record Entity(
            @JsonProperty("type") String type,
            @JsonProperty("value") String value,
            @JsonProperty("origin") String origin,
            @JsonProperty("query_span") QuerySpan querySpan,
            @JsonProperty("confidence") Double confidence) {

        QueryResolution.Entity toEntity() {
            require(value != null, "entities.value");
            return new QueryResolution.Entity(
                    enumOf(QueryResolution.EntityType.class, type, "entities.type"),
                    value,
                    toOrigin(origin, "entities"),
                    toSpan(querySpan, "entities"),
                    toConfidence(confidence, "entities"));
        }
    }

    public record Location(
            @JsonProperty("type") String type,
            @JsonProperty("value") String value,
            @JsonProperty("origin") String origin,
            @JsonProperty("query_span") QuerySpan querySpan,
            @JsonProperty("confidence") Double confidence) {

        QueryResolution.Location toLocation() {
            require(value != null, "locations.value");
            return new QueryResolution.Location(
                    enumOf(QueryResolution.LocationType.class, type, "locations.type"),
                    value,
                    toOrigin(origin, "locations"),
                    toSpan(querySpan, "locations"),
                    toConfidence(confidence, "locations"));
        }
    }

    public record Classification(
            @JsonProperty("type") String type,
            @JsonProperty("value") String value,
            @JsonProperty("origin") String origin,
            @JsonProperty("query_span") QuerySpan querySpan,
            @JsonProperty("confidence") Double confidence) {

        QueryResolution.Classification toClassification() {
            require(value != null, "classifications.value");
            return new QueryResolution.Classification(
                    enumOf(QueryResolution.ClassificationType.class, type, "classifications.type"),
                    value,
                    toOrigin(origin, "classifications"),
                    toSpan(querySpan, "classifications"),
                    toConfidence(confidence, "classifications"));
        }
    }

    /**
     * {@code origin} 은 비워 둘 수 없다. 없으면 명시와 추정을 구분할 수 없고, 그 구분이 F-06 의 강제 제외 판단을 좌우한다 — "AI가 추정한 조건만 충돌 → 강제 제외 근거로 사용하지
     * 않음".
     */
    private static QueryResolution.Origin toOrigin(String value, String path) {
        return enumOf(QueryResolution.Origin.class, value, path + ".origin");
    }

    private static QueryResolution.QuerySpan toSpan(QuerySpan span, String path) {
        return span == null ? null : span.toQuerySpan(path);
    }

    private static double toConfidence(Double value, String path) {
        require(value != null, path + ".confidence");
        return value;
    }

    private static <S, T> List<T> map(List<S> source, Function<S, T> mapper) {
        return source == null ? List.of() : source.stream().map(mapper).toList();
    }

    /** 리졸버가 보낸 값이 우리가 아는 enum 이 아니면 schema 불일치다. 조용히 {@code null} 로 넘기면 그 값이 검색 판단까지 흘러가므로 여기서 끊는다. */
    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String path) {
        require(value != null, path);
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("알 수 없는 " + path + " 값이다: " + value, ex);
        }
    }

    private static void require(boolean condition, String path) {
        if (!condition) {
            throw new IllegalArgumentException("리졸버 응답에 " + path + " 가 없다");
        }
    }
}
