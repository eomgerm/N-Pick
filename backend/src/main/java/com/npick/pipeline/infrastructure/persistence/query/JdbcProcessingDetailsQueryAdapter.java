package com.npick.pipeline.infrastructure.persistence.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import com.npick.pipeline.application.port.WorkerArtifactPort;
import com.npick.pipeline.application.query.ProcessingDetailsQueryPort;
import com.npick.pipeline.application.query.ProcessingDetailsResult;

@Repository
public class JdbcProcessingDetailsQueryAdapter implements ProcessingDetailsQueryPort {
    private final JdbcTemplate jdbc;
    private final ProcessingRecordReader reader;

    public JdbcProcessingDetailsQueryAdapter(JdbcTemplate jdbc, ObjectMapper mapper, WorkerArtifactPort artifacts) {
        this.jdbc = jdbc;
        this.reader = new ProcessingRecordReader(mapper, artifacts);
    }

    @Override
    public java.util.Map<Long, com.npick.pipeline.application.query.ProcessingProgressResult> findProgress(
            java.util.List<Long> runIds) {
        if (runIds.isEmpty()) return java.util.Map.of();
        var result = new java.util.HashMap<Long, com.npick.pipeline.application.query.ProcessingProgressResult>();
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                .query(
                        "SELECT r.pipeline_run_id, r.stage_states_json FROM npick.pipeline_run r JOIN npick.clip c USING (clip_id) WHERE c.deleted_at IS NULL AND r.pipeline_run_id IN (:ids)",
                        java.util.Map.of("ids", runIds),
                        (org.springframework.jdbc.core.RowCallbackHandler)
                                row -> result.put(row.getLong(1), reader.progress(row.getLong(1), row.getString(2))));
        return java.util.Map.copyOf(result);
    }

    @Override
    public ProcessingDetailsResult find(long clipId, long pipelineRunId) {
        return jdbc
                .query("""
                SELECT r.stage_states_json FROM npick.pipeline_run r JOIN npick.clip c USING (clip_id)
                WHERE r.clip_id=? AND r.pipeline_run_id=? AND c.deleted_at IS NULL
                """, (row, index) -> reader.read(pipelineRunId, row.getString(1)), clipId, pipelineRunId)
                .stream()
                .findFirst()
                .orElse(null);
    }
}
