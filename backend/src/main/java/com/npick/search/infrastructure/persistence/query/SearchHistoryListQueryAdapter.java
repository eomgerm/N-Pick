package com.npick.search.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;

import com.npick.search.application.query.SearchHistoryListQuery;
import com.npick.search.application.query.SearchHistoryRecord;
import com.npick.search.application.query.SearchHistoryResultRow;

/**
 * 「내 검색 기록」목록 조회 어댑터 (S15P21A501-198).
 *
 * <p>쿼리 2회로 끝낸다 — 실행 한 페이지, 그리고 그 실행들의 결과 전량. 실행마다 결과를 다시 묻지 않는다(N+1 금지).
 *
 * <p>결과를 <b>전량</b> 싣는 이유는 스냅샷 복원 가능 판정이 모든 행의 {@code explain_json} 을 봐야 하기 때문이다. rank=1 만 읽으면 목록이 available 이라 한 기록을
 * 상세가 unavailable 로 판정할 수 있다 — 계약이 금지한다.
 *
 * <p>정렬은 최신순(created_at DESC, search_execution_id DESC) 고정이다. 시각 동률에도 결정적 순서를 보장한다.
 */
@Repository
public class SearchHistoryListQueryAdapter implements SearchHistoryListQuery {

    private static final String SQL = """
            SELECT
            """ + SearchHistoryRows.ITEM_COLUMNS + """
            FROM search_execution se
            WHERE
            """ + SearchHistoryRows.OWNER_SCOPE + """
            ORDER BY se.created_at DESC, se.search_execution_id DESC
            LIMIT :size OFFSET :offset
            """;

    /** 목록과 같은 범위 조건을 쓴다. 조건이 갈라지면 노출 건수와 총계가 어긋난다. */
    private static final String COUNT_SQL = """
            SELECT count(*) FROM search_execution se WHERE
            """ + SearchHistoryRows.OWNER_SCOPE;

    private final EntityManager entityManager;

    SearchHistoryListQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SearchHistoryRecord> findByOwner(long ownerId, int page, int size) {
        List<Tuple> executions = entityManager
                .createNativeQuery(SQL, Tuple.class)
                .setParameter("ownerId", ownerId)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();
        if (executions.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SearchHistoryResultRow>> resultsByExecution = findResults(executions);
        return executions.stream()
                .map(row -> new SearchHistoryRecord(
                        SearchHistoryRows.item(row),
                        resultsByExecution.getOrDefault(SearchHistoryRows.executionIdOf(row), List.of())))
                .toList();
    }

    @Override
    public long countByOwner(long ownerId) {
        Number count = (Number) entityManager
                .createNativeQuery(COUNT_SQL)
                .setParameter("ownerId", ownerId)
                .getSingleResult();
        return count.longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<Long, List<SearchHistoryResultRow>> findResults(List<Tuple> executions) {
        List<Long> executionIds =
                executions.stream().map(SearchHistoryRows::executionIdOf).toList();
        List<Tuple> rows = entityManager
                .createNativeQuery(SearchHistoryRows.RESULT_SQL, Tuple.class)
                .setParameter("executionIds", executionIds)
                .getResultList();
        Map<Long, List<SearchHistoryResultRow>> grouped = new LinkedHashMap<>();
        for (Tuple row : rows) {
            grouped.computeIfAbsent(SearchHistoryRows.executionIdOf(row), key -> new ArrayList<>())
                    .add(SearchHistoryRows.resultRow(row));
        }
        return grouped;
    }
}
