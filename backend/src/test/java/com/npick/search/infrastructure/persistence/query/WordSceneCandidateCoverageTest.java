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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.infrastructure.config.SceneCandidateProperties;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 실제 ParadeDB 후보 집합과 원 질의 고유 토큰 커버리지의 점수·순위를 함께 검증한다. */
class WordSceneCandidateCoverageTest {
    private static String url;
    private Connection connection;
    private SingleConnectionDataSource dataSource;

    @BeforeAll
    static void migrateOwnDatabase() {
        url = NpickPostgres.freshDatabase("npick_scene_coverage_test");
        NpickPostgres.migrate(url);
    }

    @BeforeEach
    void loadFixture() throws Exception {
        connection = DriverManager.getConnection(url, NpickPostgres.username(), NpickPostgres.password());
        connection.setAutoCommit(false);
        execute("SET LOCAL search_path = npick, public");
        execute(resource("search/scene-candidate-fixture.sql"));
        execute(resource("search/expanded-phrase-fixture.sql"));
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
    void zeroWeightPreservesOrderedTokenBm25RawScore() throws Exception {
        var tokens = java.util.stream.IntStream.rangeClosed(1, 24)
                .mapToObj(index -> "term" + index)
                .toList();
        insertScenes("(90,11,22,0,1000,'" + String.join(" ", tokens) + "', '"
                + String.join(" ", tokens.reversed()) + "', 'b_roll',now(),now()),"
                + "(91,11,22,0,1000,'" + String.join(" ", tokens.subList(0, 12)) + "', '"
                + String.join(" ", tokens.subList(12, 24)) + "', 'b_roll',now(),now())");
        execute("INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,"
                + "caption_tokens,transcript_tokens,shot_type,created_at,updated_at) "
                + "SELECT 100 + n * 100 + m, 11, 22, 0, 1000, 'term' || n, NULL, 'b_roll', now(), now() "
                + "FROM generate_series(1, 24) n, generate_series(1, n) m");
        var actual = adapter(1, 1, 0, 0, 1000).findByWords(tokens, List.of());
        var parameters = new MapSqlParameterSource("tokens", String.join(" ", tokens));
        var expected = new NamedParameterJdbcTemplate(dataSource).query("""
                WITH query_tokens AS (
                    SELECT token FROM unnest(string_to_array(:tokens, ' ')) AS token
                )
                SELECT s.scene_id, paradedb.score(s) AS raw_score
                FROM npick.scene s
                JOIN npick.clip c ON c.clip_id = s.clip_id
                    AND c.active_pipeline_run_id = s.pipeline_run_id
                    AND c.deleted_at IS NULL
                WHERE s @@@ paradedb.boolean(should => ARRAY[
                    paradedb.boost(1.0::real, paradedb.boolean(should => ARRAY(
                        SELECT paradedb.term('caption_tokens', token) FROM query_tokens))),
                    paradedb.boost(1.0::real, paradedb.boolean(should => ARRAY(
                        SELECT paradedb.term('transcript_tokens', token) FROM query_tokens)))])
                ORDER BY raw_score DESC, s.scene_id ASC
                """, parameters, (row, number) ->
                new Object[] {row.getLong("scene_id"), row.getDouble("raw_score")});

        assertThat(sceneIds(actual))
                .containsExactlyElementsOf(
                        expected.stream().map(row -> (Long) row[0]).toList());
        assertThat(actual)
                .extracting(SceneCandidateResult::rawScore)
                .containsExactlyElementsOf(
                        expected.stream().map(row -> (Double) row[1]).toList());
    }

    @Test
    void positiveWeightRewardsMoreDistinctOriginalTokensWithoutRemovingCandidates() throws Exception {
        insertScenes("(90,11,22,0,1000,'정치인 옷 입 빨갛 색 " + "배경 ".repeat(5)
                + "',NULL,'b_roll',now(),now()),"
                + "(91,11,22,0,1000,'정치인 옷 옷 옷',NULL,'b_roll',now(),now())");
        var tokens = List.of("정치인", "옷", "입", "빨갛", "색");
        var baseline = adapter(0).findByWords(tokens, List.of());
        var boosted = adapter(0.5).findByWords(tokens, List.of());

        System.out.println("coverage ranking baseline=" + baseline + "; boosted=" + boosted);
        assertThat(sceneIds(boosted)).containsExactlyInAnyOrderElementsOf(sceneIds(baseline));
        assertThat(sceneIds(baseline)).containsExactly(91L, 90L);
        assertThat(only(baseline, 90).rawScore() / only(baseline, 91).rawScore())
                .isBetween(0.7, 1.0);
        assertThat(sceneIds(boosted).indexOf(90L)).isLessThan(sceneIds(baseline).indexOf(90L));
        assertThat(sceneIds(boosted)).containsExactly(90L, 91L);
        assertThat(only(boosted, 90).matchedQueryTokenCount()).isEqualTo(5);
        assertThat(only(boosted, 91).matchedQueryTokenCount()).isEqualTo(2);
        assertThat(only(boosted, 91).coverageRatio()).isEqualTo(0.4);
        assertThat(only(boosted, 91).coverageBonus()).isEqualTo(0.2);
        assertThat(only(boosted, 90).rawScore()).isEqualTo(only(baseline, 90).rawScore());
    }

    @Test
    void repeatedTokenAcrossFieldsAndOcrCountsOnce() throws Exception {
        execute("INSERT INTO keyframe VALUES (43,30,500,'test/f43')");
        execute("INSERT INTO ocr_observation VALUES (53,43,'화재 단독','화재 단독 단독',0.9,'{}')");
        var candidate = only(adapter(0.5).findByWords(List.of("화재", "단독"), List.of()), 30);
        assertThat(candidate.matchedQueryTokenCount()).isEqualTo(2);
        assertThat(candidate.queryTokenCount()).isEqualTo(2);
        assertThat(candidate.coverageRatio()).isEqualTo(1);
        assertThat(candidate.coverageBonus()).isEqualTo(0.5);
    }

    @Test
    void duplicateInputTokenDoesNotChangeDenominatorOrRanking() {
        var once = adapter(0.5).findByWords(List.of("화재", "단독"), List.of());
        var duplicate = adapter(0.5).findByWords(List.of("화재", "화재", "단독"), List.of());
        assertThat(duplicate)
                .allSatisfy(candidate -> assertThat(candidate.queryTokenCount()).isEqualTo(2));
        assertThat(duplicate).containsExactlyElementsOf(once);
    }

    @Test
    void singleOriginalTokenAndExpandedOnlyHitsKeepLegacyOrder() throws Exception {
        insertScenes("(90,11,22,0,1000,'짜장면',NULL,'b_roll',now(),now())");
        var off = adapter(0).findByWords(List.of("짜장면"), List.of(List.of("중국", "음식")));
        var on = adapter(0.5).findByWords(List.of("짜장면"), List.of(List.of("중국", "음식")));
        assertThat(sceneIds(on)).containsExactlyElementsOf(sceneIds(off)).containsExactlyInAnyOrder(90L, 61L);
        assertThat(on).allSatisfy(candidate -> {
            assertThat(candidate.queryTokenCount()).isEqualTo(1);
            assertThat(candidate.coverageBonus()).isZero();
        });
        assertThat(only(on, 90).matchedQueryTokenCount()).isEqualTo(1);
        assertThat(only(on, 90).coverageRatio()).isEqualTo(1);
        assertThat(only(on, 61).matchedQueryTokenCount()).isZero();
        assertThat(only(on, 61).coverageRatio()).isZero();
    }

    @Test
    void finiteExtremeWeightDoesNotOverflowBeforeDividingByQueryTokenCount() {
        var candidates = adapter(Double.MAX_VALUE).findByWords(List.of("화재", "단독"), List.of());
        var complete = only(candidates, 30);
        assertThat(complete.coverageRatio()).isEqualTo(1);
        assertThat(complete.coverageBonus()).isEqualTo(Double.MAX_VALUE);
        assertThat(complete.score()).isFinite();
        var partial = only(candidates, 31);
        assertThat(partial.coverageRatio()).isEqualTo(0.5);
        assertThat(partial.coverageBonus()).isEqualTo(Double.MAX_VALUE / 2);
        assertThat(partial.score()).isFinite();
    }

    @Test
    void minimumSubnormalWeightRoundsHalfCoverageToZeroAndThreeQuartersUp() {
        var candidates = adapter(Double.MIN_VALUE).findByWords(List.of("화재", "단독"), List.of());
        var complete = only(candidates, 30);
        assertThat(complete.coverageBonus()).isEqualTo(Double.MIN_VALUE);
        var partial = only(candidates, 31);
        assertThat(partial.coverageRatio()).isEqualTo(0.5);
        assertThat(partial.coverageBonus()).isZero();
        assertThat(partial.score()).isFinite();
        var threeQuarters =
                only(adapter(Double.MIN_VALUE).findByWords(List.of("공장", "화재", "단독", "없는토큰"), List.of()), 30);
        assertThat(threeQuarters.coverageRatio()).isEqualTo(0.75);
        assertThat(threeQuarters.coverageBonus()).isEqualTo(Double.MIN_VALUE);
    }

    @Test
    void expandedPhrasesNeverEnterOriginalTokenCoverage() {
        var candidates = adapter(0.5).findByWords(List.of("화재", "단독"), List.of(List.of("중국", "음식")));
        assertThat(candidates)
                .allSatisfy(candidate -> assertThat(candidate.queryTokenCount()).isEqualTo(2));
        var expandedOnly = only(candidates, 61);
        assertThat(expandedOnly.rawScore()).isPositive();
        assertThat(expandedOnly.matchedQueryTokenCount()).isZero();
        assertThat(expandedOnly.coverageRatio()).isZero();
        assertThat(expandedOnly.coverageBonus()).isZero();
        assertThat(only(candidates, 30).matchedQueryTokenCount()).isEqualTo(2);
    }

    @Test
    void disabledOcrFieldCannotContributeCoverage() {
        var explaining = new ExplainingJdbcTemplate(dataSource);
        var candidate = only(adapter(explaining, 1, 1, 0, 0.5, 1000).findByWords(List.of("화재", "단독"), List.of()), 30);
        assertThat(candidate.ocrScore()).isZero();
        assertThat(candidate.matchedQueryTokenCount()).isEqualTo(1);
        assertThat(candidate.coverageRatio()).isEqualTo(0.5);
        assertThat(explaining.executedOcrParadeDbCustomScanCount()).isEqualTo(1);
    }

    @Test
    void disabledCaptionFieldCannotContributeCoverage() {
        var candidate = only(adapter(0, 1, 1, 0.5, 1000).findByWords(List.of("공장", "단독"), List.of()), 30);
        assertThat(candidate.textScore()).isZero();
        assertThat(candidate.matchedQueryTokenCount()).isEqualTo(1);
        assertThat(candidate.coverageRatio()).isEqualTo(0.5);
    }

    @Test
    void disabledTranscriptFieldCannotContributeCoverage() {
        var candidate = only(adapter(1, 0, 1, 0.5, 1000).findByWords(List.of("기자", "원인"), List.of()), 31);
        assertThat(candidate.matchedQueryTokenCount()).isEqualTo(1);
        assertThat(candidate.coverageRatio()).isEqualTo(0.5);
    }

    @Test
    void bothTextFieldsDisabledLeaveOnlyOcrCoverage() {
        var candidates = adapter(0, 0, 1, 0.5, 1000).findByWords(List.of("화재", "단독"), List.of());
        assertThat(sceneIds(candidates)).containsExactlyInAnyOrder(30L, 34L);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.textScore()).isZero();
            assertThat(candidate.matchedQueryTokenCount()).isEqualTo(1);
            assertThat(candidate.coverageBonus()).isEqualTo(0.25);
        });
    }

    @Test
    void broadOcrScanAndNativeTokenizerPreservePerTokenHits() throws Exception {
        insertScenes("(90,11,22,0,1000,NULL,NULL,'b_roll',now(),now())");
        execute("INSERT INTO keyframe VALUES (90,90,0,'test/f90'),(91,90,500,'test/f91')");
        execute("INSERT INTO ocr_observation VALUES "
                + "(90,90,'mixed case','FIRE   alert alert',0.9,'{}'),"
                + "(91,91,'mixed whitespace',E'alert\\n\\tfire',0.9,'{}')");
        var parameters = new MapSqlParameterSource("tokens", "fire alert absent");
        var perToken = queryOcrTokenHits(PER_TOKEN_OCR_HITS_SQL, parameters);
        var broad = queryOcrTokenHits(BROAD_OCR_HITS_SQL, parameters);

        assertThat(broad)
                .containsExactlyElementsOf(perToken)
                .containsExactly("90:alert", "90:fire", "91:alert", "91:fire");
        var candidate =
                only(adapter(0, 0, 1, 0.5, 1000).findByWords(List.of("fire", "alert", "absent"), List.of()), 90);
        assertThat(candidate.matchedQueryTokenCount()).isEqualTo(2);
        assertThat(candidate.queryTokenCount()).isEqualTo(3);
    }

    @Test
    void coverageOcrUsesOneBroadParadeDbScanInsteadOfOneScanPerToken() {
        var explaining = new ExplainingJdbcTemplate(dataSource);
        adapter(explaining, 0, 0, 1, 0.5, 1000).findByWords(List.of("화재", "현장", "속보", "단독"), List.of());

        assertThat(explaining.ocrParadeDbCustomScanCount()).isEqualTo(2);
    }

    @Test
    void matchedTokenAggregateExecutesOnceForManyCandidates() throws Exception {
        execute("INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,"
                + "caption_tokens,transcript_tokens,shot_type,created_at,updated_at) "
                + "SELECT 100 + n, 11, 22, 0, 1000, '대통령 연설', NULL, 'b_roll', now(), now() "
                + "FROM generate_series(1, 407) n");
        var explaining = new ExplainingJdbcTemplate(dataSource);

        var candidates = adapter(explaining, 1, 1, 1, 0.5, 1000).findByWords(List.of("대통령", "연설"), List.of());

        assertThat(candidates).hasSize(407);
        assertThat(explaining.maxSceneAggregateLoops()).isEqualTo(1);
        assertThat(explaining.materializedMatchedTokenAggregateLoops()).isEqualTo(1);
    }

    @Test
    void zeroWeightNormalizesRawScoresWithoutChangingRawOrder() {
        var candidates = adapter(0).findByWords(List.of("화재", "단독"), List.of());
        assertThat(candidates.getFirst().score()).isEqualTo(1);
        assertThat(candidates)
                .extracting(SceneCandidateResult::rawScore)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        double maximum = candidates.getFirst().rawScore();
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.queryTokenCount()).isEqualTo(2);
            assertThat(candidate.rawScore()).isCloseTo(candidate.textScore() + candidate.ocrScore(), within(0.000001));
            assertThat(candidate.score()).isCloseTo(candidate.rawScore() / maximum, within(0.000001));
            assertThat(candidate.coverageBonus()).isZero();
        });
    }

    @Test
    void scoreAddsCoverageAfterNormalizationAndPoolLimitFollowsRanking() {
        var tokens = List.of("화재", "단독");
        var all = adapter(0.5).findByWords(tokens, List.of());
        double maximum =
                all.stream().mapToDouble(SceneCandidateResult::rawScore).max().orElseThrow();
        assertThat(all).allSatisfy(candidate -> {
            assertThat(candidate.queryTokenCount()).isEqualTo(2);
            assertThat(candidate.score())
                    .isCloseTo(candidate.rawScore() / maximum + candidate.coverageBonus(), within(0.000001));
        });
        assertThat(adapter(1, 1, 1, 0.5, 1).findByWords(tokens, List.of())).containsExactly(all.getFirst());
    }

    @Test
    void tiedScoresUseAscendingSceneIdAndExcludeInactiveAndDeletedScenes() throws Exception {
        insertScenes("(91,11,22,0,1000,'동률 하나',NULL,'b_roll',now(),now()),"
                + "(90,11,22,0,1000,'동률 하나',NULL,'b_roll',now(),now()),"
                + "(92,10,20,0,1000,'동률 하나',NULL,'b_roll',now(),now()),"
                + "(93,12,23,0,1000,'동률 하나',NULL,'b_roll',now(),now())");
        var candidates = adapter(0.5).findByWords(List.of("동률", "하나"), List.of());
        assertThat(sceneIds(candidates)).containsExactly(90L, 91L);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.score()).isEqualTo(1.5);
            assertThat(candidate.matchedQueryTokenCount()).isEqualTo(2);
        });
    }

    private WordSceneCandidateAdapter adapter(double coverage) {
        return adapter(1, 1, 1, coverage, 1000);
    }

    private WordSceneCandidateAdapter adapter(
            double caption, double transcript, double ocr, double coverage, int pool) {
        return adapter(new NamedParameterJdbcTemplate(dataSource), caption, transcript, ocr, coverage, pool);
    }

    private WordSceneCandidateAdapter adapter(
            NamedParameterJdbcTemplate template,
            double caption,
            double transcript,
            double ocr,
            double coverage,
            int pool) {
        return new WordSceneCandidateAdapter(
                template,
                new SceneCandidateProperties(
                        "test-coverage", caption, transcript, ocr, 0.3, coverage, pool, List.of()));
    }

    private List<String> queryOcrTokenHits(String sql, MapSqlParameterSource parameters) {
        return new NamedParameterJdbcTemplate(dataSource)
                .query(sql, parameters, (row, number) -> row.getLong("keyframe_id") + ":" + row.getString("token"));
    }

    private static SceneCandidateResult only(List<SceneCandidateResult> candidates, long sceneId) {
        return candidates.stream()
                .filter(candidate -> candidate.sceneId() == sceneId)
                .findFirst()
                .orElseThrow();
    }

    private static List<Long> sceneIds(List<SceneCandidateResult> candidates) {
        return candidates.stream().map(SceneCandidateResult::sceneId).toList();
    }

    private void insertScenes(String values) throws Exception {
        execute("INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,"
                + "caption_tokens,transcript_tokens,shot_type,created_at,updated_at) VALUES " + values);
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String resource(String path) throws Exception {
        try (var stream = WordSceneCandidateCoverageTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final String PER_TOKEN_OCR_HITS_SQL = """
            WITH query_tokens AS (
                SELECT token FROM unnest(string_to_array(:tokens, ' ')) AS token
            )
            SELECT o.keyframe_id, q.token
            FROM query_tokens q
            JOIN npick.ocr_observation o ON o @@@ paradedb.term('tokens', q.token)
            ORDER BY o.keyframe_id, q.token
            """;

    private static final String BROAD_OCR_HITS_SQL = """
            WITH query_tokens AS (
                SELECT token FROM unnest(string_to_array(:tokens, ' ')) AS token
            ),
            ocr_documents AS MATERIALIZED (
                SELECT o.keyframe_id, o.tokens
                FROM npick.ocr_observation o
                WHERE o @@@ paradedb.boolean(should => ARRAY(
                    SELECT paradedb.term('tokens', token) FROM query_tokens))
            )
            SELECT o.keyframe_id, q.token
            FROM ocr_documents o
            JOIN query_tokens q
                ON q.token = ANY(pdb.tokenize_whitespace(o.tokens::pdb.whitespace))
            ORDER BY o.keyframe_id, q.token
            """;

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

        int ocrParadeDbCustomScanCount() {
            try {
                JsonNode root = new ObjectMapper().readTree(plan).get(0).path("Plan");
                List<JsonNode> nodes = new ArrayList<>();
                collectPlanNodes(root, nodes);
                return (int) nodes.stream()
                        .filter(node -> "ocr_observation"
                                .equals(node.path("Relation Name").asString()))
                        .filter(node -> "ParadeDB Base Scan"
                                .equals(node.path("Custom Plan Provider").asString()))
                        .count();
            } catch (Exception exception) {
                throw new AssertionError("실행 계획 JSON을 읽지 못했다", exception);
            }
        }

        int executedOcrParadeDbCustomScanCount() {
            try {
                JsonNode root = new ObjectMapper().readTree(plan).get(0).path("Plan");
                List<JsonNode> nodes = new ArrayList<>();
                collectPlanNodes(root, nodes);
                return (int) nodes.stream()
                        .filter(node -> "ocr_observation"
                                .equals(node.path("Relation Name").asString()))
                        .filter(node -> "ParadeDB Base Scan"
                                .equals(node.path("Custom Plan Provider").asString()))
                        .filter(node -> node.path("Actual Loops").asInt() > 0)
                        .count();
            } catch (Exception exception) {
                throw new AssertionError("실행 계획 JSON을 읽지 못했다", exception);
            }
        }

        int maxSceneAggregateLoops() {
            try {
                JsonNode root = new ObjectMapper().readTree(plan).get(0).path("Plan");
                List<JsonNode> nodes = new ArrayList<>();
                collectPlanNodes(root, nodes);
                return nodes.stream()
                        .filter(node ->
                                "Aggregate".equals(node.path("Node Type").asString()))
                        .filter(node -> node.path("Group Key").toString().contains("scene_id"))
                        .mapToInt(node -> node.path("Actual Loops").asInt())
                        .max()
                        .orElseThrow();
            } catch (Exception exception) {
                throw new AssertionError("실행 계획 JSON을 읽지 못했다", exception);
            }
        }

        int materializedMatchedTokenAggregateLoops() {
            try {
                JsonNode root = new ObjectMapper().readTree(plan).get(0).path("Plan");
                List<JsonNode> nodes = new ArrayList<>();
                collectPlanNodes(root, nodes);
                return nodes.stream()
                        .filter(node ->
                                "Aggregate".equals(node.path("Node Type").asString()))
                        .filter(node -> "CTE matched_tokens"
                                .equals(node.path("Subplan Name").asString()))
                        .findFirst()
                        .orElseThrow()
                        .path("Actual Loops")
                        .asInt();
            } catch (Exception exception) {
                throw new AssertionError("실행 계획 JSON을 읽지 못했다", exception);
            }
        }

        private static void collectPlanNodes(JsonNode node, List<JsonNode> nodes) {
            nodes.add(node);
            for (JsonNode child : node.path("Plans")) collectPlanNodes(child, nodes);
        }
    }
}
