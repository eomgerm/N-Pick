package com.npick.search.application.port;

import java.time.LocalDate;
import java.util.List;

/**
 * 질의 리졸버가 낸 검증된 resolution.
 *
 * <p>질의 리졸버 모듈의 {@code ValidatedResolution} (`ai/src/npick_worker/query_resolver/schema.py`, {@code query-resolver/v2})
 * 에 대응한다. 그 파일이 출력 schema 의 정본이므로, 필드가 바뀌면 여기도 같이 바뀐다.
 *
 * <p>리졸버 JSON 의 키 표기는 infrastructure 의 response DTO 가 떠안는다.
 */
public record QueryResolution(
        String schemaVersion,
        Intent intent,
        List<DateWindow> dateWindows,
        List<IncidentName> incidentNames,
        List<Entity> entities,
        List<Location> locations,
        List<Classification> classifications,
        List<String> expandedTerms,
        double confidence) {

    public enum Intent {
        SCENE_SEARCH,
        RECENT_SCENE,
        UNKNOWN
    }

    /**
     * 값의 출처. 사용자가 직접 명시한 것과 AI 가 추정한 것을 구분한다 (FRD v3.1 F-05).
     *
     * <p>{@link #EXPLICIT_FILTER} 는 사용자가 UI 에서 고른 필터의 자리다. 리졸버는 이 값을 낼 수 없고, 내면 리졸버 쪽 validator 가 {@link #INFERRED} 로
     * 강등한다 — F-05 "사용자가 직접 입력한 필터는 AI나 규칙이 바꿀 수 없다".
     */
    public enum Origin {
        EXPLICIT_FILTER,
        EXPLICIT_QUERY,
        INFERRED
    }

    /**
     * 방송일과 촬영일을 구분한다 (FRD v3.1 F-06).
     *
     * <p>이름은 F-04 날짜 태그({@code filmed_date}, {@code broadcast_date}) 와 같다. 저장 태그와 해석 JSON 키가 갈리면 날짜 충돌 판정마다 매핑이 필요해지므로
     * 일부러 맞췄다.
     */
    public enum DateField {
        BROADCAST_DATE,
        FILMED_DATE
    }

    public enum EntityType {
        PERSON,
        ORGANIZATION
    }

    public enum LocationType {
        LOCATION,
        FACILITY
    }

    /** 분류 축. 값 어휘는 아직 닫혀 있지 않아 {@code value} 는 자유 문자열이다 (FRD v3.1 F-04). */
    public enum ClassificationType {
        SEASON,
        WEATHER,
        SCENE_TYPE
    }

    /**
     * 원문 질의 기준 {@code [start, end)} 반열린 구간.
     *
     * <p>정규화 질의가 아니라 <b>사용자가 친 원문</b> 기준이다.
     */
    public record QuerySpan(int start, int end) {}

    public record DateWindow(
            DateField field,
            LocalDate start,
            LocalDate endExclusive,
            Origin origin,
            QuerySpan querySpan,
            double confidence) {}

    public record IncidentName(String value, Origin origin, QuerySpan querySpan, double confidence) {}

    public record Entity(EntityType type, String value, Origin origin, QuerySpan querySpan, double confidence) {}

    public record Location(LocationType type, String value, Origin origin, QuerySpan querySpan, double confidence) {}

    public record Classification(
            ClassificationType type, String value, Origin origin, QuerySpan querySpan, double confidence) {}
}
