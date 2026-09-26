package com.npick.clip.infrastructure.persistence.query;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.clip.application.query.analysis.ClipAnalysisScenesQueryPort;

@Repository
public class JdbcClipAnalysisScenesQueryAdapter implements ClipAnalysisScenesQueryPort {
    private final JdbcTemplate jdbc;

    public JdbcClipAnalysisScenesQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RunScope> findRunScope(long clipId, long pipelineRunId) {
        return jdbc
                .query("""
                        SELECT c.active_pipeline_run_id = r.pipeline_run_id AS search_applied
                        FROM npick.clip c
                        JOIN npick.pipeline_run r ON r.clip_id = c.clip_id
                        WHERE c.clip_id = ? AND r.pipeline_run_id = ? AND c.deleted_at IS NULL
                        """, (row, rowNumber) -> new RunScope(row.getBoolean("search_applied")), clipId, pipelineRunId)
                .stream()
                .findFirst();
    }

    @Override
    public List<SceneCoverage> findCoverage(long clipId, long pipelineRunId) {
        return jdbc.query(
                """
                SELECT scene_id,
                       NULLIF(BTRIM(caption), '') IS NOT NULL AS captioned,
                       NULLIF(BTRIM(transcript_text), '') IS NOT NULL AS transcripted
                FROM npick.scene
                WHERE clip_id = ? AND pipeline_run_id = ?
                ORDER BY start_time_ms, scene_id
                """,
                (row, rowNumber) -> new SceneCoverage(
                        row.getLong("scene_id"), row.getBoolean("captioned"), row.getBoolean("transcripted")),
                clipId,
                pipelineRunId);
    }

    @Override
    public List<SceneRow> findPage(long clipId, long pipelineRunId, int offset, int size) {
        return jdbc.query(
                """
                WITH ordered AS (
                    SELECT s.*,
                           ROW_NUMBER() OVER (ORDER BY s.start_time_ms, s.scene_id) AS scene_index
                    FROM npick.scene s
                    WHERE s.clip_id = ? AND s.pipeline_run_id = ?
                )
                SELECT s.scene_id, s.scene_index, s.start_time_ms, s.end_time_ms,
                       (SELECT k.timestamp_ms
                        FROM npick.keyframe k
                        WHERE k.scene_id = s.scene_id
                        ORDER BY k.keyframe_id
                        LIMIT 1) AS representative_frame_timestamp_ms,
                       NULLIF(BTRIM(s.caption), '') AS caption,
                       s.shot_type,
                       NULLIF(BTRIM(s.transcript_text), '') AS transcript_text,
                       s.transcript_source,
                       ARRAY(
                           SELECT observed.raw_text
                           FROM (
                               SELECT o.raw_text,
                                      MIN(k.keyframe_id) AS first_keyframe_id,
                                      MIN(o.ocr_observation_id) AS first_observation_id
                               FROM npick.keyframe k
                               JOIN npick.ocr_observation o ON o.keyframe_id = k.keyframe_id
                               WHERE k.scene_id = s.scene_id AND BTRIM(o.raw_text) <> ''
                               GROUP BY o.raw_text
                           ) observed
                           ORDER BY observed.first_keyframe_id, observed.first_observation_id
                       ) AS ocr_texts
                FROM ordered s
                ORDER BY s.start_time_ms, s.scene_id
                OFFSET ? LIMIT ?
                """,
                (row, rowNumber) -> new SceneRow(
                        row.getLong("scene_id"),
                        row.getInt("scene_index"),
                        row.getLong("start_time_ms"),
                        row.getLong("end_time_ms"),
                        row.getObject("representative_frame_timestamp_ms", Long.class),
                        row.getString("caption"),
                        row.getString("shot_type"),
                        row.getString("transcript_text"),
                        row.getString("transcript_source"),
                        strings(row.getArray("ocr_texts"))),
                clipId,
                pipelineRunId,
                offset,
                size);
    }

    private static List<String> strings(Array value) throws SQLException {
        if (value == null) return List.of();
        return List.copyOf(Arrays.asList((String[]) value.getArray()));
    }
}
