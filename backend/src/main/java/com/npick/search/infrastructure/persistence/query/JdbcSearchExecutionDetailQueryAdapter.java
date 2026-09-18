package com.npick.search.infrastructure.persistence.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.npick.search.application.query.execution.SearchExecutionDetail;
import com.npick.search.application.query.execution.SearchExecutionDetailQueryPort;

@Repository
public class JdbcSearchExecutionDetailQueryAdapter implements SearchExecutionDetailQueryPort {
    private static final String EXECUTION_SQL = """
            SELECT * FROM npick.search_execution
             WHERE search_execution_id=? AND (searched_by_id=? OR ?)
            """;
    private static final String RESULTS_SQL = """
            SELECT search_result_id, scene_id, result_rank, explain_json::text AS explain_json
              FROM npick.search_result WHERE search_execution_id=? ORDER BY result_rank
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcSearchExecutionDetailQueryAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<SearchExecutionDetail> findVisible(long executionId, long requesterId, boolean reviewer) {
        List<SearchExecutionDetail> rows = jdbc.query(
                EXECUTION_SQL,
                (row, index) -> execution(row, results(executionId)),
                executionId,
                requesterId,
                reviewer);
        return rows.stream().findFirst();
    }

    private List<SearchExecutionDetail.Result> results(long executionId) {
        return jdbc.query(
                RESULTS_SQL,
                (row, index) -> new SearchExecutionDetail.Result(
                        row.getLong("search_result_id"),
                        row.getLong("scene_id"),
                        row.getInt("result_rank"),
                        json(row.getString("explain_json"))),
                executionId);
    }

    private SearchExecutionDetail execution(ResultSet row, List<SearchExecutionDetail.Result> results)
            throws SQLException {
        return new SearchExecutionDetail(
                row.getLong("search_execution_id"),
                row.getString("execution_type"),
                nullableLong(row, "replay_of_feedback_id"),
                row.getString("status"),
                row.getString("query_text"),
                row.getString("normalized_query"),
                json(row.getString("explicit_filters_json")),
                json(row.getString("normalized_filters_json")),
                row.getString("query_fingerprint"),
                row.getString("normalization_version"),
                json(row.getString("degraded_reasons_json")),
                row.getString("error_code"),
                row.getString("parse_source"),
                json(row.getString("resolver_output_json")),
                json(row.getString("parsed_query_json")),
                json(row.getString("applied_rules_json")),
                nullableInt(row, "parse_ms"),
                json(row.getString("candidates_json")),
                json(row.getString("filtered_json")),
                json(row.getString("applied_excludes_json")),
                json(row.getString("search_config_json")),
                row.getString("config_version"),
                nullableInt(row, "execution_ms"),
                json(row.getString("verification_context_json")),
                results,
                instant(row.getObject("created_at")),
                instant(row.getObject("updated_at")));
    }

    private JsonNode json(String value) {
        return value == null ? null : mapper.readTree(value);
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        return ((Timestamp) value).toInstant();
    }
}
