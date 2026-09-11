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
