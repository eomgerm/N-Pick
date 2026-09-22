package com.npick.search.infrastructure.persistence.query;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.npick.search.application.port.SearchHistorySoftDeletePort;

/**
 * 「내 검색 기록」 숨김 어댑터 (S15P21A501-276).
 *
 * <p>읽기 어댑터와 <b>같은 패키지</b>에 둔다. 대상 판정 조건이 {@link SearchHistoryRows#OWNER_SCOPE_ANY_STATE} 하나
 * 여야 하기 때문이다 — 조건을 다른 패키지에 복사하면 목록에 보이는 기록을 지우지 못하거나 그 반대가 된다. 같은 이유로
 * {@code SearchHistoryRows} 의 주석이 목록·상세·총계를 한 상수로 묶어 두었다.
 *
 * <p>{@code COALESCE} 로 이미 숨긴 행의 시각을 유지한다. 다시 {@code now()} 를 쓰면 재시도할 때마다 숨긴 시각이
 * 밀려 보존기간 판단의 기준이 흔들린다. 갱신 건수는 <b>존재·소유 여부</b>만 뜻하며 숨김 여부와 무관하다.
 */
@Repository
public class SearchHistorySoftDeleteAdapter implements SearchHistorySoftDeletePort {

    private static final String SQL =
            """
            UPDATE search_execution se
            SET deleted_at = COALESCE(se.deleted_at, now()), updated_at = now()
            WHERE se.search_execution_id = :searchExecutionId AND
            """
                    + SearchHistoryRows.OWNER_SCOPE_ANY_STATE;

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
}
