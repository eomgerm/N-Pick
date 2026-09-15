package com.npick.search.infrastructure.persistence.query;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import javax.sql.DataSource;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.DenseSearchErrorCode;
import com.npick.search.application.query.dense.DenseCandidatesResult;
import com.npick.search.application.query.dense.DenseCandidatesResult.Candidate;
import com.npick.search.application.query.dense.DenseCandidatesResult.Coverage;
import com.npick.search.application.query.dense.DenseCandidatesResult.Reason;
import com.npick.search.application.query.dense.DenseCandidatesResult.Status;
import com.npick.search.application.query.dense.DenseQuery;
import com.npick.search.application.query.dense.DenseQueryValidation;
import com.npick.search.application.query.dense.DenseSearchSettings;
import com.npick.search.application.query.dense.FindDenseCandidatesQueryPort;

/**
 * Exact pgvector cosine candidates. The single statement reads coverage and hits from the same snapshot.
 *
 * <p>Required producer contract: write scene.embedding and the successful text_embedding stage's
 * versions.detail.modelVersion atomically from the same generation result. PipelineRun already persists the versions
 * envelope at this path, but the text_embedding storage adapter is not yet implemented. Missing/legacy metadata is
 * never inferred from pipeline_version or the current runtime model.
 *
 * <p>A JDBC savepoint isolates statement failures on the caller's connection, preserving both its transaction snapshot
 * and uncommitted verification changes. No REQUIRES_NEW snapshot is substituted. Connection, schema, recovery and
 * unknown DB failures propagate; an unavailable channel does not promise that lexical search succeeds. Search assembly
 * owns that decision.
 */
@Repository
class DenseSceneCandidateAdapter implements FindDenseCandidatesQueryPort {
    private static final String SQL = """
            WITH eligible AS MATERIALIZED (
                SELECT s.scene_id, s.clip_id, s.pipeline_run_id, s.embedding,
                       r.stage_states_json->>'schemaVersion' AS schema_version,
                       r.stage_states_json#>>'{stages,text_embedding,status}' AS stage_status,
                       r.stage_states_json#>'{stages,text_embedding,versions,detail,modelVersion}' AS model_json,
                       r.stage_states_json#>>'{stages,text_embedding,versions,detail,modelVersion}' AS model_version
                FROM npick.scene s
                JOIN npick.clip c ON c.clip_id = s.clip_id
                    AND c.active_pipeline_run_id = s.pipeline_run_id AND c.deleted_at IS NULL
                JOIN npick.pipeline_run r ON r.pipeline_run_id = s.pipeline_run_id AND r.clip_id = s.clip_id
            ), checked AS MATERIALIZED (
                SELECT *, CASE
                    WHEN embedding IS NULL THEN 'missing_vector'
                    WHEN schema_version IS DISTINCT FROM 'npick.stage_states/v1'
                        OR stage_status IS DISTINCT FROM 'succeeded' THEN 'stage_unavailable'
                    WHEN jsonb_typeof(model_json) IS DISTINCT FROM 'string'
                        OR model_version !~ '^[^[:space:]@]+@[0-9a-f]{40}$' THEN 'missing_model'
                    WHEN model_version <> ? THEN 'model_mismatch'
                    WHEN public.vector_dims(embedding) <> 1024 OR public.vector_norm(embedding) = 0
                        THEN 'invalid_vector'
                    END AS exclusion_reason
                FROM eligible
            ), measured AS MATERIALIZED (
                SELECT *, CASE WHEN exclusion_reason IS NULL
                    THEN embedding OPERATOR(public.<=>) CAST(? AS public.vector) END AS distance
                FROM checked
            ), usable AS MATERIALIZED (
                SELECT * FROM measured WHERE distance >= 0 AND distance <= 2
            ), coverage AS (
                SELECT count(*) AS eligible,
                       count(*) FILTER (WHERE embedding IS NULL) AS missing,
                       count(*) FILTER (WHERE embedding IS NOT NULL)
                           - (SELECT count(*) FROM usable) AS unusable,
                       (SELECT count(*) FROM usable) AS usable,
                       count(*) FILTER (WHERE exclusion_reason = 'stage_unavailable') AS unavailable_stage,
                       count(*) FILTER (WHERE exclusion_reason = 'missing_model') AS missing_model,
                       count(*) FILTER (WHERE exclusion_reason = 'model_mismatch') AS model_mismatch,
                       count(*) FILTER (WHERE exclusion_reason = 'invalid_vector'
                           OR (exclusion_reason IS NULL AND NOT (distance >= 0 AND distance <= 2))) AS invalid_vector
                FROM measured
            ), hits AS (
                SELECT * FROM usable ORDER BY distance, scene_id LIMIT ?
            )
            SELECT coverage.*, hits.scene_id, hits.clip_id, hits.pipeline_run_id,
                   hits.distance, hits.model_version
            FROM coverage LEFT JOIN hits ON true ORDER BY hits.distance, hits.scene_id
            """;

    // Only failures local to this vector statement. Undefined tables, connection loss, aborted
    // caller transactions and resource exhaustion are deliberately not degraded successes.
    private static final Set<String> CHANNEL_FAILURES = Set.of("42883", "42704", "42725", "57014");
    private final JdbcTemplate jdbc;

    DenseSceneCandidateAdapter(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public DenseCandidatesResult find(DenseQuery query, DenseSearchSettings settings) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(settings, "settings");
        Reason invalid = DenseQueryValidation.validate(query, settings);
        if (invalid != Reason.NONE) return DenseCandidatesResult.unavailable(invalid, query, settings);
        String vector = normalizedLiteral(query.embedding());
        try {
            return jdbc.execute((ConnectionCallback<DenseCandidatesResult>) connection -> {
                Savepoint savepoint = null;
                try {
                    if (!connection.getAutoCommit()) savepoint = connection.setSavepoint();
                    DenseCandidatesResult result = read(connection, query, settings, vector);
                    if (savepoint != null) connection.releaseSavepoint(savepoint);
                    return result;
                } catch (SQLException failure) {
                    if (savepoint != null) {
                        try {
                            connection.rollback(savepoint);
                            connection.releaseSavepoint(savepoint);
                        } catch (SQLException recoveryFailure) {
                            failure.addSuppressed(recoveryFailure);
                            throw new BusinessException(DenseSearchErrorCode.DATABASE_UNAVAILABLE, failure);
                        }
                    }
                    if (CHANNEL_FAILURES.contains(failure.getSQLState())) {
                        return DenseCandidatesResult.unavailable(Reason.DENSE_QUERY_FAILED, query, settings);
                    }
                    throw new BusinessException(DenseSearchErrorCode.DATABASE_UNAVAILABLE, failure);
                }
            });
        } catch (CannotGetJdbcConnectionException failure) {
            throw new BusinessException(DenseSearchErrorCode.DATABASE_UNAVAILABLE, failure);
        }
    }

    private DenseCandidatesResult read(
            Connection connection, DenseQuery query, DenseSearchSettings settings, String vector) throws SQLException {
        try (var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, settings.modelVersion());
            statement.setString(2, vector);
            statement.setInt(3, settings.poolSize());
            try (var rows = statement.executeQuery()) {
                var candidates = new ArrayList<Candidate>();
                Coverage coverage = null;
                while (rows.next()) {
                    if (coverage == null)
                        coverage = new Coverage(
                                rows.getLong("eligible"),
                                rows.getLong("missing"),
                                rows.getLong("unusable"),
                                rows.getLong("usable"),
                                rows.getLong("unavailable_stage"),
                                rows.getLong("missing_model"),
                                rows.getLong("model_mismatch"),
                                rows.getLong("invalid_vector"));
                    long sceneId = rows.getLong("scene_id");
                    if (rows.wasNull()) continue;
                    double distance = rows.getDouble("distance");
                    candidates.add(new Candidate(
                            sceneId,
                            rows.getLong("clip_id"),
                            rows.getLong("pipeline_run_id"),
                            candidates.size() + 1,
                            distance,
                            1 - distance,
                            rows.getString("model_version")));
                }
                boolean incomplete = coverage != null && coverage.unusableVectors() > 0;
                return new DenseCandidatesResult(
                        incomplete ? Status.PARTIAL : Status.AVAILABLE,
                        incomplete ? Reason.STORED_VECTOR_UNAVAILABLE : Reason.NONE,
                        candidates,
                        settings.snapshot(),
                        query.modelVersion(),
                        coverage);
            }
        }
    }

    private static String normalizedLiteral(float[] vector) {
        double norm = 0;
        for (float value : vector) norm += (double) value * value;
        norm = Math.sqrt(norm);
        var literal = new StringJoiner(",", "[", "]");
        for (float value : vector) literal.add(Float.toString((float) (value / norm)));
        return literal.toString();
    }
}
