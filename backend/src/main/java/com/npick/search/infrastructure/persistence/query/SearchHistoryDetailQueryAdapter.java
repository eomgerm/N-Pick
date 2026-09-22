package com.npick.search.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;

import com.npick.search.application.query.SearchHistoryDetailQuery;
import com.npick.search.application.query.SearchHistoryRecord;
import com.npick.search.application.query.SearchHistoryResultRow;

/**
 * 「내 검색 기록」상세 조회 어댑터 (S15P21A501-198).
 *
 * <p>{@link SearchHistoryListQueryAdapter} 와 <b>같은</b> SELECT 목록·범위 조건을 쓴다({@link SearchHistoryRows}). 소유자 조건은 WHERE 에
 * 있으므로 타인 소유·미존재·대상 밖(replay 등)이 모두 빈 값으로 나온다 — 호출자가 구분 없이 같은 404 로 바꿔 존재 여부를 노출하지 않는다.
 */
@Repository
public class SearchHistoryDetailQueryAdapter implements SearchHistoryDetailQuery {

    private static final String SQL = """
            SELECT
            """ + SearchHistoryRows.ITEM_COLUMNS + """
            FROM search_execution se
            WHERE se.search_execution_id = :searchExecutionId AND
            """ + SearchHistoryRows.OWNER_SCOPE;

    private final EntityManager entityManager;

    SearchHistoryDetailQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<SearchHistoryRecord> findByOwner(long searchExecutionId, long ownerId) {
        List<Tuple> executions = entityManager
                .createNativeQuery(SQL, Tuple.class)
                .setParameter("searchExecutionId", searchExecutionId)
                .setParameter("ownerId", ownerId)
                .getResultList();
        if (executions.isEmpty()) {
            return Optional.empty();
        }
        Tuple execution = executions.get(0);
        return Optional.of(new SearchHistoryRecord(SearchHistoryRows.item(execution), findResults(searchExecutionId)));
    }

    @SuppressWarnings("unchecked")
    private List<SearchHistoryResultRow> findResults(long searchExecutionId) {
        List<Tuple> rows = entityManager
                .createNativeQuery(SearchHistoryRows.RESULT_SQL, Tuple.class)
                .setParameter("executionIds", List.of(searchExecutionId))
                .getResultList();
        return rows.stream().map(SearchHistoryRows::resultRow).toList();
    }
}
