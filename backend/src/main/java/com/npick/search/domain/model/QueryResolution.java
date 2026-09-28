package com.npick.search.domain.model;

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

    /** AI 해석 없이 돌 때 쓰는 빈 해석 이름. 실제 schema 를 흉내 내지 않는다 — 기록에서 리졸버 출력과 구분돼야 한다. */
    public static final String NO_AI_SCHEMA = "query-resolver/none";

    /**
     * 조건이 하나도 없는 해석 (FRD §6.2 의 fallback).
     *
     * <p>해석이 없다고 {@code null} 을 들고 다니면 뒤 단계마다 분기가 생기고, 그 분기 하나를 빠뜨리면 사용자가 직접 건 명시 필터까지 조용히 사라진다. 빈 해석을 만들어 두면 명시 필터는 그
     * 위에 그대로 얹히고, 구조화 점수·guard 는 활성 조건이 없어 자연히 0점·판정 없음이 된다.
     *
     * <p><b>없던 해석을 지어내는 것이 아니다.</b> 여기 담기는 조건은 사용자가 화면에서 직접 지정한 것뿐이고, AI 가 추정한 것은 하나도 들어가지 않는다.
     */
    public static QueryResolution withoutAiInterpretation() {
        return new QueryResolution(
                NO_AI_SCHEMA,
                Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.0);
    }

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
