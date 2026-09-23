package com.npick.search.infrastructure.persistence.query;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.npick.search.application.port.SearchHistorySoftDeletePort;

/**
 * 「내 검색 기록」 숨김 어댑터 (S15P21A501-276).
 *
 * <p>읽기 어댑터와 <b>같은 패키지</b>에 둔다. 대상 판정 조건이 {@link SearchHistoryRows#OWNER_SCOPE_ANY_STATE} 하나 여야 하기 때문이다 — 조건을 다른 패키지에
 * 복사하면 목록에 보이는 기록을 지우지 못하거나 그 반대가 된다. 같은 이유로 {@code SearchHistoryRows} 의 주석이 목록·상세·총계를 한 상수로 묶어 두었다.
 *
 * <p>{@code COALESCE} 로 이미 숨긴 행의 시각을 유지한다. 다시 {@code now()} 를 쓰면 재시도할 때마다 숨긴 시각이 밀려 보존기간 판단의 기준이 흔들린다. 갱신 건수는 <b>존재·소유
 * 여부</b>만 뜻하며 숨김 여부와 무관하다.
 *
 * <p>{@code updated_at} 도 같은 이유로 처음 숨길 때만 쓴다. 이 값은 감사 조회 응답( {@code SearchExecutionDetailResponse})에 나가므로, 아무것도 바꾸지 않은
 * 재시도가 감사에 보이는 필드를 흔들면 안 된다.
 */
@Repository
public class SearchHistorySoftDeleteAdapter implements SearchHistorySoftDeletePort {

    private static final String SQL = """
            UPDATE search_execution se
            SET deleted_at = COALESCE(se.deleted_at, now()),
                updated_at = CASE WHEN se.deleted_at IS NULL THEN now() ELSE se.updated_at END
            WHERE se.search_execution_id = :searchExecutionId AND
            """ + SearchHistoryRows.OWNER_SCOPE_ANY_STATE;

    /**
     * 전체 삭제(S15P21A501-291)는 이미 숨긴 행을 조건으로 걸러 낸다({@code deleted_at IS NULL}). 그래서 건별과 달리 COALESCE·CASE 가 필요 없다 —
     * 대상은 항상 아직 보이는 행이라 {@code now()} 를 그대로 써도 이미 숨긴 기록의 시각을 밀지 않는다.
     */
    private static final String CLEAR_SQL = """
            UPDATE search_execution se
            SET deleted_at = now(), updated_at = now()
            WHERE se.deleted_at IS NULL AND
            """ + SearchHistoryRows.OWNER_SCOPE_ANY_STATE;

    private final EntityManager entityManager;

    SearchHistorySoftDeleteAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean hideOwned(long searchExecutionId, long ownerId) {
        return entityManager
                        .createNativeQuery(SQL)
                        .setParameter("searchExecutionId", searchExecutionId)
                        .setParameter("ownerId", ownerId)
                        .executeUpdate()
                > 0;
    }

    @Override
    public int hideAllOwned(long ownerId) {
        return entityManager
                .createNativeQuery(CLEAR_SQL)
                .setParameter("ownerId", ownerId)
                .executeUpdate();
    }
}
