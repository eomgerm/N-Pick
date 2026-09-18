package com.npick.search.infrastructure.persistence.query;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchExecutionErrorCode;
import com.npick.search.application.query.search.ExecuteSearchQuery;
import com.npick.search.application.query.search.VerificationInput;
import com.npick.search.application.query.search.VerificationInputPort;

/**
 * 신고의 원 검색 실행에서 검색어·명시 필터를 읽는다 (F-12 2: 검수자가 다시 입력하지 않는다).
 * feedback → search_result → search_execution 경로로 원 실행을 찾는다.
 */
@Repository
class VerificationInputQueryAdapter implements VerificationInputPort {

    private final EntityManager em;
    private final ObjectMapper objectMapper;

    VerificationInputQueryAdapter(EntityManager em, ObjectMapper objectMapper) {
        this.em = em;
        this.objectMapper = objectMapper;
    }

    @Override
    public VerificationInput load(long feedbackId) {
        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT se.query_text, se.explicit_filters_json::text FROM npick.feedback f "
                                + "JOIN npick.search_result sr ON sr.search_result_id = f.search_result_id "
                                + "JOIN npick.search_execution se ON se.search_execution_id = sr.search_execution_id "
                                + "WHERE f.feedback_id = :fid")
                .setParameter("fid", feedbackId)
                .getSingleResult();
        String rawQuery = (String) row[0];
        return new VerificationInput(rawQuery, parseDateFilters((String) row[1]));
    }

    /**
     * 실제 형태(JdbcSearchExecutionRecordAdapter.explicitFilters)는 {@code {"broadcast_date": {"from":..,"to":..},
     * "filmed_date": {...}}} 다 — 브리프가 추정한 {@code {field:[from,to]}} 배열이 아니다. 없으면 none().
     */
    private ExecuteSearchQuery.DateFilters parseDateFilters(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null ? "{}" : json);
            return new ExecuteSearchQuery.DateFilters(
                    date(node, "broadcast_date", "from"), date(node, "broadcast_date", "to"),
                    date(node, "filmed_date", "from"), date(node, "filmed_date", "to"));
        } catch (Exception malformed) {
            throw new BusinessException(SearchExecutionErrorCode.EXECUTION_NOT_RECORDED, malformed);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> loadOriginalResultSceneIds(long feedbackId) {
        List<Number> rows = em.createNativeQuery(
                        "SELECT sr2.scene_id FROM npick.feedback f "
                                + "JOIN npick.search_result sr ON sr.search_result_id = f.search_result_id "
                                + "JOIN npick.search_result sr2 ON sr2.search_execution_id = sr.search_execution_id "
                                + "WHERE f.feedback_id = :fid ORDER BY sr2.result_rank")
                .setParameter("fid", feedbackId)
                .getResultList();
        return rows.stream().map(Number::longValue).toList();
    }

    private LocalDate date(JsonNode node, String field, String key) {
        JsonNode range = node.get(field);
        if (range == null || range.isNull()) {
            return null;
        }
        JsonNode value = range.get(key);
        return value == null || value.isNull() ? null : LocalDate.parse(value.asText());
    }
}
