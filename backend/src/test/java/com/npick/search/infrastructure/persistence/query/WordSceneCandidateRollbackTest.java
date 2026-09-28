package com.npick.search.infrastructure.persistence.query;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.infrastructure.config.SceneCandidateProperties;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

class WordSceneCandidateRollbackTest {
    private static String url;
    private Connection connection;
    private SingleConnectionDataSource dataSource;

    @BeforeAll
    static void migrateOwnDatabase() {
        url = NpickPostgres.freshDatabase("npick_scene_rollback_test");
        NpickPostgres.migrate(url);
    }

    @BeforeEach
    void loadFixture() throws Exception {
        connection = DriverManager.getConnection(
                url + "?prepareThreshold=1", NpickPostgres.username(), NpickPostgres.password());
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL search_path = npick, public");
            statement.execute("SET LOCAL plan_cache_mode = force_generic_plan");
            statement.execute(resource("search/scene-candidate-fixture.sql"));
            statement.execute(resource("search/expanded-phrase-fixture.sql"));
        }
        dataSource = new SingleConnectionDataSource(connection, true);
    }

    @AfterEach
    void rollbackFixture() throws Exception {
        if (dataSource != null) dataSource.destroy();
        if (connection != null && !connection.isClosed()) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void zeroCoverageWeightSkipsCoverageWorkAndReturnsSafeFields() throws Exception {
        var zeroPlan = new ExplainingJdbcTemplate(dataSource);
        var zero = adapter(zeroPlan, 0).findByWords(List.of("화재", "단독"), List.of());
        assertThat(zeroPlan.genericExplainPlanCount()).isEqualTo(1);
        var positivePlan = new ExplainingJdbcTemplate(dataSource);
        var positive = adapter(positivePlan, 0.5).findByWords(List.of("화재", "단독"), List.of());

        assertThat(positivePlan.genericExplainPlanCount()).isEqualTo(2);
        assertThat(zeroPlan.coverageCtePlanNodes()).isZero();
        assertThat(positivePlan.coverageCtePlanNodes()).isEqualTo(2);
        assertThat(zero).isNotEmpty();
        assertThat(zero).allSatisfy(candidate -> {
            assertThat(candidate.queryTokenCount()).isEqualTo(2);
            assertThat(candidate.matchedQueryTokenCount()).isZero();
            assertThat(candidate.coverageRatio()).isZero();
            assertThat(candidate.coverageBonus()).isZero();
        });
        assertThat(zero)
                .extracting(candidate -> candidate.sceneId())
                .containsExactlyElementsOf(positive.stream()
                        .sorted(java.util.Comparator.comparingDouble(SceneCandidateResult::rawScore)
                                .reversed()
                                .thenComparingLong(SceneCandidateResult::sceneId))
                        .map(candidate -> candidate.sceneId())
                        .toList());
    }

    private WordSceneCandidateAdapter adapter(NamedParameterJdbcTemplate template, double coverageWeight) {
        return new WordSceneCandidateAdapter(
                template,
                new SceneCandidateProperties("test-rollback", 1.0, 1.0, 1.0, 0.3, coverageWeight, 1000, List.of()));
    }

    private static String resource(String path) throws Exception {
        try (var stream = WordSceneCandidateRollbackTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class ExplainingJdbcTemplate extends NamedParameterJdbcTemplate {
        private final NamedParameterJdbcTemplate explainer;
        private String plan;

        ExplainingJdbcTemplate(SingleConnectionDataSource dataSource) {
            super(dataSource);
            explainer = new NamedParameterJdbcTemplate(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, SqlParameterSource parameters, RowMapper<T> rowMapper) {
            plan = explainer.queryForObject(
                    "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, parameters, (row, number) -> row.getString(1));
            return super.query(sql, parameters, rowMapper);
        }

        int coverageCtePlanNodes() throws Exception {
            var nodes = new ArrayList<JsonNode>();
            collectNodes(new ObjectMapper().readTree(plan).get(0).path("Plan"), nodes);
            return (int) nodes.stream()
                    .filter(node -> "CTE matched_tokens"
                                    .equals(node.path("Subplan Name").asString())
                            || "CTE ocr_documents"
                                    .equals(node.path("Subplan Name").asString()))
                    .count();
        }

        int genericExplainPlanCount() {
            return explainer
                    .getJdbcTemplate()
                    .queryForObject(
                            "SELECT coalesce(sum(generic_plans), 0)::integer FROM pg_prepared_statements "
                                    + "WHERE statement LIKE 'EXPLAIN (ANALYZE%'",
                            Integer.class);
        }

        private static void collectNodes(JsonNode node, List<JsonNode> nodes) {
            nodes.add(node);
            for (JsonNode child : node.path("Plans")) collectNodes(child, nodes);
        }
    }
}
