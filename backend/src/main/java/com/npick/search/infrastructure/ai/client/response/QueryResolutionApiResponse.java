package com.npick.search.infrastructure.ai.client.response;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.search.application.error.QueryResolverErrorCode;
import com.npick.search.application.port.AnchorFinding;
import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.query.dense.DenseQuery;
import com.npick.search.domain.model.QueryResolution;

/**
 * 질의 리졸버 응답 본문. 리졸버 모듈의 {@code ResolutionResult} 계약을 그대로 받는다.
 *
 * <p>이 DTO 가 리졸버의 snake_case 표기를 떠안아 application 의 {@link QueryResolution} 이 외부 표현에 묶이지 않게 한다 (설계 정본 §10).
 *
 * <p>질의 임베딩({@code embedding}·{@code embedding_model_version})은 해석과 독립된 축이다 — 해석이 성공해도 없을 수 있고, 그때 빠지는 것은 dense 채널 하나다
 * (S15P21A501-164).
 *
 * <p>없는 필드를 조용히 기본값으로 채우지 않는다. 키 이름이 어긋나면 오류 없이 빈 해석이 성공으로 흘러가고, 그러면 검색은 AI 해석을 받은 것처럼 보이면서 아무 조건도 걸리지 않는다.
 */
public record QueryResolutionApiResponse(
        @JsonProperty("normalization") Normalization normalization,
        @JsonProperty("resolution") Resolution resolution,
        @JsonProperty("findings") List<Finding> findings,
        @JsonProperty("embedding") float[] embedding,
        @JsonProperty("embedding_model_version") String embeddingModelVersion,
        @JsonProperty("resolution_schema_version") String resolutionSchemaVersion,
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("error") ResolverError error) {

    public QueryResolutionResult toResult() {
        require(normalization != null, "normalization");
        QueryNormalization normalized = normalization.toNormalization();

        if (resolution == null) {
            // 해석은 실패했지만 정규화는 살아 있다. 호출부가 이 토큰으로 BM25 로 간다.
            //
            // error 까지 없으면 계약 위반이다. 그래도 예외로 던지지 않는다 — 정규화는 이미
            // 파싱됐고, 여기서 던지면 어댑터가 RESOLVER_SCHEMA_INVALID 로 올려 그 토큰이
            // 사라진다. 계약을 어긴 쪽을 벌하려다 검색을 같이 죽이는 셈이다 (§6.2).
            return failed(
                    normalized, error == null ? QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID : error.toErrorCode());
        }
        try {
            // 둘 다 오면 어느 쪽이 진짜인지 알 수 없다. 성공으로 밀면 error 를 조용히 버린다.
            require(error == null, "resolution 과 error 가 동시에 왔다 — error");
            require(resolutionSchemaVersion != null, "resolution_schema_version");
            require(promptVersion != null, "prompt_version");
            require(modelVersion != null, "model_version");

            return new QueryResolutionResult(
                    normalized,
                    resolution.toResolution(),
                    map(findings, Finding::toFinding),
                    toQueryEmbedding(),
                    resolutionSchemaVersion,
                    promptVersion,
                    modelVersion,
                    null);
        } catch (RuntimeException ex) {
            // 해석 부분만 못 읽었다. 정규화는 이미 파싱됐으므로 버리지 않는다 — 그 토큰이 없으면
            // FRD v3.1 §6.2 의 원 검색어 BM25 fallback 자체가 불가능해진다.
            return failed(normalized, QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID);
        }
    }

    private static QueryResolutionResult failed(QueryNormalization normalized, QueryResolverErrorCode failure) {
        return new QueryResolutionResult(normalized, null, List.of(), null, null, null, null, failure);
    }

    /**
     * 질의 벡터를 그대로 옮긴다. <b>여기서 검사하지 않는다</b> — 차원·유효값·모델 일치는 {@code DenseQueryValidation} 의 몫이고, 그쪽이 사유를 구분해
     * {@code DenseCandidatesResult.Reason} 으로 돌려준다. 여기서 미리 걸러 {@code null} 로 만들면 "리졸버가 안 줬다" 와 "줬는데 못 쓴다" 가 같은 모양이 되어
     * degraded 안내와 기록이 둘을 구분하지 못한다.
     *
     * <p>{@code embedding_error} 는 읽지 않는다. 호출부가 필요로 하는 것은 "쓸 벡터가 있는가" 하나이고, 없는 이유의 분류는 dense 채널이 자기 어휘로 다시 낸다.
     *
     * @return 벡터가 없으면 {@code null}. 모델 버전이 비어 있어도 벡터가 있으면 만들어 넘긴다
     */
    private DenseQuery toQueryEmbedding() {
        return embedding == null ? null : new DenseQuery(embedding, embeddingModelVersion);
    }

    public record Normalization(
            @JsonProperty("normalized_query") String normalizedQuery,
            @JsonProperty("search_tokens") List<String> searchTokens,
            @JsonProperty("normalization_version") String normalizationVersion) {

        QueryNormalization toNormalization() {
            require(normalizedQuery != null, "normalization.normalized_query");
            require(searchTokens != null, "normalization.search_tokens");
            require(normalizationVersion != null, "normalization.normalization_version");
            return new QueryNormalization(normalizedQuery, List.copyOf(searchTokens), normalizationVersion);
        }
    }

    /**
     * 리졸버가 분류한 실패 사유.
     *
     * <p>{@code category} 는 {@link QueryResolverErrorCode} 의 enum 이름과 1:1 이다. 정본은
     * {@code ai/src/npick_worker/query_resolver/*_backend.py} 의 상수다.
     *
     * <p>{@code message} 는 쓰지 않는다 — 리졸버가 category 만 보내기로 했고(FRD v3.1 §6.4 "외부 호출 기록은 원문 대신 처리 종류·성공/실패를 남긴다"), 상세는 워커
     * 로그에 있다.
     */
    public record ResolverError(
            @JsonProperty("category") String category,
            @JsonProperty("message") String message) {

        QueryResolverErrorCode toErrorCode() {
            require(category != null, "error.category");
            try {
                return QueryResolverErrorCode.valueOf(category);
            } catch (IllegalArgumentException ex) {
                // 우리가 모르는 사유다. 실패라는 사실은 유지하고 분류만 포기한다.
                return QueryResolverErrorCode.RESOLVER_FAILED;
            }
        }
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
            // 리졸버는 이 배열들을 항상 보낸다 (`schema.py` 의 기본값이 빈 튜플이라 비어도 키는 있다).
            // null 이면 키가 개명됐거나 계약이 깨진 것이다. 없이 통과시키면 해석이 텅 빈 채로
            // "성공" 이 되어 검색 품질만 조용히 떨어지고 §7.2 기록에도 아무 흔적이 남지 않는다.
            require(dateWindows != null, "resolution.date_windows");
            require(incidentNames != null, "resolution.incident_names");
            require(entities != null, "resolution.entities");
            require(locations != null, "resolution.locations");
            require(classifications != null, "resolution.classifications");
            require(expandedTerms != null, "resolution.expanded_terms");
            return new QueryResolution(
                    schemaVersion,
                    enumOf(QueryResolution.Intent.class, intent, "resolution.intent"),
                    map(dateWindows, DateWindow::toDateWindow),
                    map(incidentNames, IncidentName::toIncidentName),
                    map(entities, Entity::toEntity),
                    map(locations, Location::toLocation),
                    map(classifications, Classification::toClassification),
                    List.copyOf(expandedTerms),
                    toConfidence(confidence, "resolution"));
        }
    }

    public record Finding(
            @JsonProperty("path") String path,
            @JsonProperty("action") String action,
            @JsonProperty("reason") String reason) {

        AnchorFinding toFinding() {
            require(path != null, "findings.path");
            require(action != null, "findings.action");
            require(reason != null, "findings.reason");
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
            @JsonProperty("start") String start,
            @JsonProperty("end_exclusive") String endExclusive,
            @JsonProperty("origin") String origin,
            @JsonProperty("query_span") QuerySpan querySpan,
            @JsonProperty("confidence") Double confidence) {

        QueryResolution.DateWindow toDateWindow() {
            return new QueryResolution.DateWindow(
                    enumOf(QueryResolution.DateField.class, field, "date_windows.field"),
                    date(start, "date_windows.start"),
                    date(endExclusive, "date_windows.end_exclusive"),
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

    /**
     * {@code explicit_query} 인데 span 이 없는 경우를 BE 가 막지 않는다 — 리졸버의 {@code validator.py} 가 그 조합을 이미 {@code inferred} 로
     * 강등하기 때문이다. 그 보장이 사라지면 여기에 검증을 넣어야 한다 (FRD v3.1 F-05 의 명시/추정 구분).
     */
    private static QueryResolution.QuerySpan toSpan(QuerySpan span, String path) {
        return span == null ? null : span.toQuerySpan(path);
    }

    /**
     * 리졸버는 날짜를 {@code YYYY-MM-DD} 문자열로 보낸다(schema.py 의 {@code start: str}).
     *
     * <p>DTO 필드를 {@code LocalDate} 로 두면 안 된다 — 형식이 어긋날 때 Jackson 이 본문 전체 역직렬화를 실패시켜 {@code normalization} 까지 사라지고, BM25
     * fallback 재료를 잃는다. 리졸버 쪽 validator 는 {@code date.fromisoformat} 을 쓰므로 {@code "20240301"} 같은 값도 통과시킨다. 여기서 파싱하면 그
     * 경우가 해석 실패로만 남는다.
     */
    private static LocalDate date(String value, String path) {
        require(value != null, path);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("%s 가 YYYY-MM-DD 가 아니다: %s".formatted(path, value), ex);
        }
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
