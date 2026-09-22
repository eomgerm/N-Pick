package com.npick.search.infrastructure.persistence.query;

import java.time.Instant;
import java.time.OffsetDateTime;

import jakarta.persistence.Tuple;

import com.npick.search.application.query.SearchHistoryItem;
import com.npick.search.application.query.SearchHistoryResultRow;

/**
 * 「내 검색 기록」목록·상세가 공유하는 SQL 조각과 행 매핑 (S15P21A501-198).
 *
 * <p>둘이 <b>같은</b> 소유·대상 조건과 같은 SELECT 목록을 써야 한다. 조건이 갈라지면 목록에 보이는 기록의 상세가 404 가
 * 되거나 그 반대가 된다. 그래서 문자열을 각 어댑터에 복사하지 않고 여기 한 벌만 둔다.
 *
 * <p>JSONB 는 {@code CAST(... AS text)} 로 원문 문자열로 꺼낸다. 조회층이 Jackson 을 알지 않게 하고, 해석은 응답 조립
 * 지점({@code SearchSnapshot}) 한 곳에서만 한다.
 */
final class SearchHistoryRows {

    /** 헤더 SELECT 목록. 별칭 {@code se} 를 전제한다. */
    static final String ITEM_COLUMNS =
            """
            se.search_execution_id, se.query_text, se.created_at, se.status, se.parse_source,
            CAST(se.explicit_filters_json AS text) AS explicit_filters_json,
            CAST(se.degraded_reasons_json AS text) AS degraded_reasons_json,
            CAST(se.applied_rules_json AS text) AS applied_rules_json,
            CAST(se.applied_excludes_json AS text) AS applied_excludes_json,
            CAST(se.filtered_json AS text) AS filtered_json
            """;

    /**
     * 소유·대상 범위에서 숨김 여부만 뺀 조건. 숨기기(S15P21A501-276)가 대상을 고를 때 쓴다 — 이미 숨긴 기록도 같은
     * 요청으로 다시 받을 수 있어야 하므로 {@code deleted_at} 을 보지 않는다.
     */
    static final String OWNER_SCOPE_ANY_STATE =
            """
            se.searched_by_id = :ownerId
              AND se.execution_type = 'original'
              AND se.status IN ('succeeded', 'degraded')
            """;

    /**
     * 조회 대상 범위. 본인이 실행한 original 중 결과가 확정된 것만 본다. replay·running·failed 는 DB 에 보존하되
     * 이 화면에서 제외한다(S15P21A501-185). 목록·상세·총계가 모두 이 조건을 쓴다.
     *
     * <p>숨긴 기록도 여기서 빠진다(S15P21A501-276). 목록에서만 빼고 상세를 열어 두면 URL 로 그대로 열리므로 숨김이
     * 아니다. 감사 조회와 신고·검수는 이 조건을 쓰지 않으므로 영향이 없다.
     */
    static final String OWNER_SCOPE = OWNER_SCOPE_ANY_STATE
            + """
              AND se.deleted_at IS NULL
            """;

    /**
     * 결과 행 조회. 실행 id 여러 개를 한 번에 받아 목록의 N+1 을 막는다. {@code clip_id} 는 장면을 통해서만 알 수 있어
     * scene 을 JOIN 한다 — 당시 결과가 가리킨 그 장면이며, 재처리로 생긴 새 장면이 아니다(baseline scene_id 주석).
     */
    static final String RESULT_SQL =
            """
            SELECT sr.search_execution_id, sr.search_result_id, sr.scene_id, sc.clip_id, sr.result_rank,
                   CAST(sr.explain_json AS text) AS explain_json
            FROM search_result sr
            JOIN scene sc ON sc.scene_id = sr.scene_id
            WHERE sr.search_execution_id IN (:executionIds)
            ORDER BY sr.search_execution_id, sr.result_rank
            """;

    private SearchHistoryRows() {}

    static SearchHistoryItem item(Tuple row) {
        return new SearchHistoryItem(
                ((Number) row.get("search_execution_id")).longValue(),
                (String) row.get("query_text"),
                (String) row.get("explicit_filters_json"),
                toInstant(row.get("created_at")),
                (String) row.get("status"),
                (String) row.get("degraded_reasons_json"),
                (String) row.get("parse_source"),
                (String) row.get("applied_rules_json"),
                (String) row.get("applied_excludes_json"),
                (String) row.get("filtered_json"));
    }

    static SearchHistoryResultRow resultRow(Tuple row) {
        return new SearchHistoryResultRow(
                ((Number) row.get("search_result_id")).longValue(),
                ((Number) row.get("scene_id")).longValue(),
                ((Number) row.get("clip_id")).longValue(),
                ((Number) row.get("result_rank")).intValue(),
                (String) row.get("explain_json"));
    }

    static long executionIdOf(Tuple row) {
        return ((Number) row.get("search_execution_id")).longValue();
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        return ((java.sql.Timestamp) value).toInstant();
    }
}
