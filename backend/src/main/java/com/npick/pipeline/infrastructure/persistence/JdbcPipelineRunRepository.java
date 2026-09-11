package com.npick.pipeline.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.npick.pipeline.domain.model.PipelineRun;
import com.npick.pipeline.domain.repository.PipelineRunRepository;

@Repository
public class JdbcPipelineRunRepository implements PipelineRunRepository {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JdbcPipelineRunRepository.class);
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public JdbcPipelineRunRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Candidate> candidates(Cursor after, Map<String, String> capabilities) {
        if (capabilities.isEmpty()) return List.of();
        return jdbc.query(
                """
            SELECT r.* FROM npick.pipeline_run r JOIN npick.clip c USING (clip_id)
            WHERE r.status IN ('queued','running') AND r.lease_id IS NULL AND c.deleted_at IS NULL
              AND (r.created_at, r.pipeline_run_id) > (?, ?)
              AND EXISTS (
                SELECT 1 FROM jsonb_each_text(?::jsonb) capability
                WHERE capability.value <> 'unknown'
                  AND (CASE WHEN jsonb_exists(r.stage_states_json, 'schemaVersion')
                       THEN r.stage_states_json->'stages' ELSE r.stage_states_json END)
                       ->capability.key->>'status' = 'pending')
            ORDER BY r.created_at, r.pipeline_run_id LIMIT 64
            """,
                (row, index) -> new Candidate(
                        new Cursor(instant(row, "created_at"), row.getLong("pipeline_run_id")),
                        readCandidate(row, index)),
                Timestamp.from(after == null ? Instant.parse("0001-01-01T00:00:00Z") : after.createdAt()),
                after == null ? 0L : after.id(),
                mapper.writeValueAsString(capabilities));
    }

    public Optional<PipelineRun> tryLockCandidate(long runId) {
        return jdbc.query("""
            SELECT r.* FROM npick.pipeline_run r JOIN npick.clip c USING (clip_id)
            WHERE r.pipeline_run_id=? AND r.status IN ('queued','running')
              AND r.lease_id IS NULL AND c.deleted_at IS NULL
            FOR UPDATE OF r SKIP LOCKED
            """, this::readCandidate, runId).stream()
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    public Optional<PipelineRun> lock(long id) {
        return jdbc
                .query("SELECT * FROM npick.pipeline_run WHERE pipeline_run_id=? FOR UPDATE", this::read, id)
                .stream()
                .findFirst();
    }

    public List<PipelineRun> lockExpired(Instant before) {
        return jdbc.query("""
            SELECT * FROM npick.pipeline_run WHERE status='running' AND lease_expires_at < ?
            FOR UPDATE SKIP LOCKED
            """, this::readCandidate, Timestamp.from(before)).stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public void save(PipelineRun run, Instant now) {
        var s = run.snapshot();
        jdbc.update(
                """
            UPDATE npick.pipeline_run SET status=?, error_code=?, started_at=?, finished_at=?,
            lease_id=?, lease_stage=?, lease_worker_id=?, lease_expires_at=?, lease_heartbeat_at=?,
            stage_states_json=?::jsonb, updated_at=? WHERE pipeline_run_id=?
            """,
                s.status(),
                s.errorCode(),
                timestamp(s.startedAt()),
                timestamp(s.finishedAt()),
                s.leaseId(),
                s.leaseStage(),
                s.workerId(),
                timestamp(s.leaseExpiresAt()),
                timestamp(s.heartbeatAt()),
                mapper.writeValueAsString(s.stageStates()),
                Timestamp.from(now),
                s.id());
    }

    private PipelineRun read(ResultSet row, int index) throws SQLException {
        Map<String, Object> states = mapper.readValue(row.getString("stage_states_json"), new TypeReference<>() {});
        return new PipelineRun(new PipelineRun.Snapshot(
                row.getLong("pipeline_run_id"),
                row.getLong("clip_id"),
                row.getInt("processing_no"),
                row.getString("pipeline_version"),
                row.getString("status"),
                row.getString("error_code"),
                instant(row, "started_at"),
                instant(row, "finished_at"),
                row.getObject("lease_id", UUID.class),
                row.getString("lease_stage"),
                row.getString("lease_worker_id"),
                instant(row, "lease_expires_at"),
                instant(row, "lease_heartbeat_at"),
                states));
    }

    private PipelineRun readCandidate(ResultSet row, int index) throws SQLException {
        try {
            PipelineRun run = read(row, index);
            run.nextStage();
            return run;
        } catch (com.npick.common.error.BusinessException failure) {
            LOG.warn(
                    "실행 상태 확인 필요: pipelineRunId={}, code={}",
                    row.getLong("pipeline_run_id"),
                    failure.errorCode().code());
            return null;
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
