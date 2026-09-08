package com.npick.search.application.port;

import java.time.LocalDate;
import java.util.List;

/**
 * 질의 리졸버가 돌려준 resolution 을 application 이 쓰는 형태로 표현한다.
 *
 * <p>FRD v2.2 §6.2 resolution schema 를 옮긴 것이다. 리졸버 API 의 JSON 계약은 infrastructure 의 response DTO 가 소유하며 여기로 새지 않는다.
 */
public record QueryResolution(
        String schemaVersion,
        Intent intent,
        List<DateWindow> dateWindows,
        List<IncidentName> incidentNames,
        List<Entity> entities,
        List<Location> locations,
        List<String> expandedTerms,
        double confidence) {

    public enum Intent {
        SCENE_SEARCH,
        RECENT_SCENE,
        UNKNOWN
    }

    /** 값이 query 에서 직접 온 것인지 추론된 것인지 (FR-QRY-011). */
    public enum Origin {
        EXPLICIT_FILTER,
        EXPLICIT_QUERY,
        INFERRED
    }

    /** 방송일과 촬영일을 구분한다 (FR-QRY-013). */
    public enum DateField {
        BROADCAST_DATE,
        FILMING_DATE
    }

    public enum EntityType {
        PERSON,
        ORGANIZATION
    }

    public enum LocationType {
        LOCATION,
        FACILITY
    }

    /** canonical query 안에서의 위치. explicit anchor 검증에 쓴다 (FR-QRY-011, 014). */
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
}
