package com.npick.search.infrastructure.persistence.query;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.DenseSearchErrorCode;
import com.npick.search.application.query.dense.DenseCandidatesResult;
import com.npick.search.application.query.dense.DenseCandidatesResult.Reason;
import com.npick.search.application.query.dense.DenseCandidatesResult.Status;
import com.npick.search.application.query.dense.DenseQuery;
import com.npick.search.application.query.dense.DenseSearchSettings;
import com.npick.search.infrastructure.config.SceneCandidateProperties;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Fixed vectors in real PostgreSQL/pgvector and real BM25; does not claim an AI producer round trip. */
class DenseSceneCandidateAdapterTest {
    private static final String MODEL = "dragonkue/snowflake-arctic-embed-l-v2.0-ko@" + "a".repeat(40);
    private static final DenseSearchSettings SETTINGS = new DenseSearchSettings(MODEL, 10, 2.0);
    private static String url;
    private Connection connection;
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private DenseSceneCandidateAdapter adapter;

    @BeforeAll
    static void database() {
        url = NpickPostgres.freshDatabase("npick_dense_test");
        NpickPostgres.migrate(url);
    }

    @BeforeEach
    void fixture() throws Exception {
        connection = DriverManager.getConnection(url, NpickPostgres.username(), NpickPostgres.password());
        connection.setAutoCommit(false);
        source = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("SET LOCAL search_path = npick, public");
        try (var stream = getClass().getClassLoader().getResourceAsStream("search/scene-candidate-fixture.sql")) {
            jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        jdbc.update("UPDATE npick.pipeline_run SET stage_states_json = CAST(? AS jsonb)", stage(MODEL, "succeeded"));
        vector(30, 1, 0);
        vector(31, 0.8f, 0.6f);
        vector(32, 1, 0); // inactive run
        vector(33, 0.8f, 0.6f); // tie: ascending scene ID
        vector(34, 0, 1);
        vector(35, -1, 0);
        vector(36, 1, 0); // deleted clip
        adapter = new DenseSceneCandidateAdapter(source);
    }

    @AfterEach
    void rollback() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.rollback();
            connection.close();
        }
    }

    @Test
    void cosineOrderingTiesEligibilityAndRecordedSettings() {
        var result = adapter.find(query(1, 0), new DenseSearchSettings(MODEL, 4, 2.0));
        assertThat(result.status()).isEqualTo(Status.AVAILABLE);
        assertThat(ids(result)).containsExactly(30L, 31L, 33L, 34L);
        assertThat(result.candidates()).extracting(c -> c.rank()).containsExactly(1, 2, 3, 4);
        assertThat(result.candidates().get(1).similarity()).isCloseTo(0.8, within(0.00001));
        assertThat(result.candidates().get(1).distance()).isCloseTo(0.2, within(0.00001));
        assertThat(result.candidates()).allSatisfy(c -> {
            assertThat(c.modelVersion()).isEqualTo(MODEL);
            assertThat(c.similarity()).isEqualTo(1 - c.distance());
        });
        assertThat(result.coverage().eligible()).isEqualTo(5);
        assertThat(result.coverage().usableVectors()).isEqualTo(5);
        assertThat(result.queryModelVersion()).isEqualTo(MODEL);
        assertThat(result.settings()).isEqualTo(new DenseSearchSettings(MODEL, 4, 2.0).snapshot());
        assertThat(result.settings().metric()).isEqualTo("cosine");
        assertThat(result.settings().dimension()).isEqualTo(1024);
        assertThat(adapter.find(query(10, 0), SETTINGS).candidates().getLast().similarity())
                .isEqualTo(-1);
    }

    @Test
    void aDistanceThresholdDropsFarCandidatesWithoutMovingCoverage() {
        // Fixture distances from (1,0): 30=0.0, 31=0.2, 33=0.2, 34=1.0, 35=2.0.
        var result = adapter.find(query(1, 0), new DenseSearchSettings(MODEL, 10, 0.5));
        assertThat(ids(result)).containsExactly(30L, 31L, 33L);
        assertThat(result.status()).isEqualTo(Status.AVAILABLE);
        // The usable CTE decides validity, not relevance: tightening the threshold must not
        // reclassify a distant vector as broken, which would make every search degraded.
        assertThat(result.coverage().usableVectors()).isEqualTo(5);
        assertThat(result.coverage().unusableVectors()).isZero();
        assertThat(result.coverage().invalidVectors()).isZero();
        assertThat(result.settings().maxDistance()).isEqualTo(0.5);
    }

    @Test
    void aThresholdThatExcludesEveryCandidateIsAnEmptySuccessNotADegradedChannel() {
        jdbc.update("UPDATE npick.scene SET embedding = CAST(? AS public.vector)", literal(0, 1));
        var result = adapter.find(query(1, 0), new DenseSearchSettings(MODEL, 10, 0.5));
        assertThat(result.candidates()).isEmpty();
        assertThat(result.status()).isEqualTo(Status.AVAILABLE);
        assertThat(result.reason()).isEqualTo(Reason.NONE);
        assertThat(result.coverage().usableVectors()).isEqualTo(5);
        assertThat(result.coverage().invalidVectors()).isZero();
    }

    @Test
    void missingVectorsAndEmptyEligibleSetAreSuccessfulQueriesWithDifferentCoverage() {
        jdbc.update("UPDATE npick.scene SET embedding = NULL");
        var missing = adapter.find(query(1, 0), SETTINGS);
        assertThat(missing.status()).isEqualTo(Status.AVAILABLE);
        assertThat(missing.candidates()).isEmpty();
        assertThat(missing.coverage().missingVectors()).isEqualTo(5);
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id = NULL");
        var empty = adapter.find(query(1, 0), SETTINGS);
        assertThat(empty.status()).isEqualTo(Status.AVAILABLE);
        assertThat(empty.coverage().eligible()).isZero();
        assertThat(empty.coverage().missingVectors()).isZero();
    }

    @Test
    void missingAndMismatchedStoredModelsExcludeOnlyAffectedCandidates() {
        jdbc.update("UPDATE npick.pipeline_run SET stage_states_json = '{}' WHERE pipeline_run_id = 21");
        var partial = adapter.find(query(1, 0), SETTINGS);
        assertThat(ids(partial)).containsExactly(33L, 34L, 35L);
        assertThat(partial.status()).isEqualTo(Status.AVAILABLE);
        assertThat(partial.coverage().unusableVectors()).isEqualTo(2);
        assertThat(partial.coverage().invalidVectors()).isZero();
        jdbc.update(
                "UPDATE npick.pipeline_run SET stage_states_json = CAST(? AS jsonb) WHERE pipeline_run_id = 22",
                stage("different-model@" + "b".repeat(40), "succeeded"));
        var none = adapter.find(query(1, 0), SETTINGS);
        assertThat(none.status()).isEqualTo(Status.AVAILABLE);
        assertThat(none.candidates()).isEmpty();
        assertThat(none.coverage().unusableVectors()).isEqualTo(5);
        assertThat(none.coverage().modelMismatches()).isPositive();
        assertThat(none.coverage().invalidVectors()).isZero();
    }

    @Test
    void failedStageAndLegacySchemaCannotSupplyVectors() {
        jdbc.update(
                "UPDATE npick.pipeline_run SET stage_states_json = CAST(? AS jsonb) WHERE pipeline_run_id = 21",
                stage(MODEL, "failed"));
        assertThat(ids(adapter.find(query(1, 0), SETTINGS))).containsExactly(33L, 34L, 35L);
        jdbc.update("UPDATE npick.pipeline_run SET stage_states_json = stage_states_json - 'schemaVersion'");
        assertThat(adapter.find(query(1, 0), SETTINGS).coverage().usableVectors())
                .isZero();
    }

    @Test
    void corruptZeroVectorDoesNotPoisonOtherCandidates() {
        vector(30, 0, 0);
        jdbc.update("UPDATE npick.scene SET embedding = NULL WHERE scene_id = 31");
        var result = adapter.find(query(1, 0), SETTINGS);
        assertThat(ids(result)).containsExactly(33L, 34L, 35L);
        assertThat(result.status()).isEqualTo(Status.PARTIAL);
        assertThat(result.reason()).isEqualTo(Reason.STORED_VECTOR_UNAVAILABLE);
        assertThat(result.coverage().missingVectors()).isEqualTo(1);
        assertThat(result.coverage().unusableVectors()).isEqualTo(1);
        assertThat(result.coverage().invalidVectors()).isEqualTo(1);
    }

    @Test
    void databaseRejectsWrongDimensionsAndNonFiniteStorage() throws Exception {
        for (String invalid : List.of("[1,2]", literal(Float.NaN, 0), literal(Float.POSITIVE_INFINITY, 0))) {
            var savepoint = connection.setSavepoint();
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE npick.scene SET embedding = CAST(? AS public.vector) WHERE scene_id=30", invalid))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
        assertThat(ids(adapter.find(query(1, 0), SETTINGS))).containsExactly(30L, 31L, 33L, 34L, 35L);
    }

    @Test
    void invalidQueryVectorsAndModelsAreDistinctFromEmptyHits() {
        assertReason(new DenseQuery(null, MODEL), Reason.QUERY_VECTOR_MISSING);
        assertReason(new DenseQuery(new float[3], MODEL), Reason.QUERY_DIMENSION_MISMATCH);
        assertReason(query(0, 0), Reason.QUERY_VECTOR_INVALID);
        assertReason(query(Float.NaN, 0), Reason.QUERY_VECTOR_INVALID);
        assertReason(query(Float.POSITIVE_INFINITY, 0), Reason.QUERY_VECTOR_INVALID);
        assertReason(new DenseQuery(query(1, 0).embedding(), null), Reason.QUERY_MODEL_MISSING);
        assertReason(new DenseQuery(query(1, 0).embedding(), "model@main"), Reason.QUERY_MODEL_MISSING);
        assertReason(new DenseQuery(query(1, 0).embedding(), "other@" + "a".repeat(40)), Reason.QUERY_MODEL_MISMATCH);
        assertReason(new DenseQuery(query(1, 0).embedding(), MODEL.replace('a', 'b')), Reason.QUERY_MODEL_MISMATCH);
    }

    @Test
    void finiteExtremeQueryValuesAreNormalizedBeforePgvectorArithmetic() {
        assertThat(ids(adapter.find(query(Float.MAX_VALUE, 0), SETTINGS))).containsExactly(30L, 31L, 33L, 34L, 35L);
        assertThat(ids(adapter.find(query(Float.MIN_VALUE, 0), SETTINGS))).containsExactly(30L, 31L, 33L, 34L, 35L);
    }

    @Test
    void denseFunctionFailureRecoversCallerTransactionAndRealWordSearch() {
        jdbc.execute(failingVectorFunction());
        var result = adapter.find(query(1, 0), SETTINGS);
        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(Reason.DENSE_QUERY_FAILED);
        assertThat(result.coverage()).isNull();
        assertThat(words().findByWords(List.of("제설"), List.of()))
                .extracting(c -> c.sceneId())
                .containsExactly(35L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.scene", Long.class))
                .isEqualTo(7);
    }

    @Test
    void commonSchemaFailureIsNotReportedAsDegradedSuccess() {
        jdbc.execute("ALTER TABLE npick.scene RENAME TO scene_temporarily_unavailable");
        assertThatThrownBy(() -> adapter.find(query(1, 0), SETTINGS))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DenseSearchErrorCode.DATABASE_UNAVAILABLE));
        assertThatThrownBy(() -> words().findByWords(List.of("제설"), List.of()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void connectionAcquisitionFailureIsNotReportedAsDenseOnlyFailure() {
        var unavailable = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1", "test", "test");
        assertThatThrownBy(() -> new DenseSceneCandidateAdapter(unavailable).find(query(1, 0), SETTINGS))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DenseSearchErrorCode.DATABASE_UNAVAILABLE));
    }

    @Test
    void springManagedTransactionKeepsUncommittedChangesAndRemainsCommittable() throws Exception {
        String ownUrl = NpickPostgres.freshDatabase("npick_dense_transaction_test");
        NpickPostgres.migrate(ownUrl);
        var dataSource = new DriverManagerDataSource(ownUrl, NpickPostgres.username(), NpickPostgres.password());
        String fixtureSql;
        try (var stream = getClass().getClassLoader().getResourceAsStream("search/scene-candidate-fixture.sql")) {
            fixtureSql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        template.executeWithoutResult(tx -> {
            var local = new JdbcTemplate(dataSource);
            local.execute("SET LOCAL search_path = npick, public");
            local.execute(fixtureSql);
            local.update(
                    "UPDATE npick.pipeline_run SET stage_states_json = CAST(? AS jsonb)", stage(MODEL, "succeeded"));
            local.update("UPDATE npick.scene SET embedding = CAST(? AS public.vector)", literal(1, 0));
            var dense = new DenseSceneCandidateAdapter(dataSource);
            assertThat(dense.find(query(1, 0), SETTINGS).candidates()).hasSize(5);
            local.execute("CREATE TEMP TABLE dense_outer_marker(value int)");
            local.update("INSERT INTO dense_outer_marker VALUES (1)");
            local.execute("SAVEPOINT before_dense_fault");
            local.execute(failingVectorFunction());
            var result = new DenseSceneCandidateAdapter(dataSource).find(query(1, 0), SETTINGS);
            assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(tx.isRollbackOnly()).isFalse();
            assertThat(local.queryForObject("SELECT value FROM dense_outer_marker", Integer.class))
                    .isEqualTo(1);
            var words = new WordSceneCandidateAdapter(
                    new NamedParameterJdbcTemplate(dataSource),
                    new SceneCandidateProperties("test", 1.0, 1.0, 1.0, 0.3, 10));
            assertThat(words.findByWords(List.of("제설"), List.of()))
                    .extracting(c -> c.sceneId())
                    .containsExactly(35L);
            local.execute("ROLLBACK TO SAVEPOINT before_dense_fault");
            // Return normally: the outer transaction actually commits successfully.
        });
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM npick.scene", Long.class))
                .isEqualTo(7);
    }

    private void assertReason(DenseQuery query, Reason reason) {
        var result = adapter.find(query, SETTINGS);
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.coverage()).isNull();
    }

    private WordSceneCandidateAdapter words() {
        return new WordSceneCandidateAdapter(
                new NamedParameterJdbcTemplate(source), new SceneCandidateProperties("test", 1.0, 1.0, 1.0, 0.3, 10));
    }

    private void vector(long id, float x, float y) {
        jdbc.update(
                "UPDATE npick.scene SET embedding = CAST(? AS public.vector) WHERE scene_id = ?", literal(x, y), id);
    }

    private static DenseQuery query(float x, float y) {
        float[] values = new float[1024];
        values[0] = x;
        values[1] = y;
        return new DenseQuery(values, MODEL);
    }

    private static String literal(float x, float y) {
        return Arrays.toString(query(x, y).embedding());
    }

    private static List<Long> ids(DenseCandidatesResult result) {
        return result.candidates().stream().map(c -> c.sceneId()).toList();
    }

    private static String stage(String model, String status) {
        return """
                {"schemaVersion":"npick.stage_states/v1","stages":{"text_embedding":{
                "status":"%s","versions":{"detail":{"modelVersion":"%s"}}}}}
                """.formatted(status, model);
    }

    private static String failingVectorFunction() {
        // This function is only invoked by dense, unlike removing <=> which also breaks pg_search planning.
        return """
                CREATE OR REPLACE FUNCTION public.vector_norm(public.vector) RETURNS double precision
                LANGUAGE plpgsql IMMUTABLE STRICT AS $fault$
                BEGIN RAISE EXCEPTION 'test-only dense cancellation' USING ERRCODE = '57014'; END
                $fault$
                """;
    }
}
