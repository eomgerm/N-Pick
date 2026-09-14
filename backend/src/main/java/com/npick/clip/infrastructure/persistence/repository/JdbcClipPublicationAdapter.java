package com.npick.clip.infrastructure.persistence.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.clip.application.port.ClipPublicationPort;
import com.npick.clip.domain.model.ClipPublication;
import com.npick.clip.infrastructure.config.ClipRegistrationProperties;

@Repository
public class JdbcClipPublicationAdapter implements ClipPublicationPort {
    private final JdbcTemplate jdbc;
    private final ClipRegistrationProperties properties;

    public JdbcClipPublicationAdapter(JdbcTemplate jdbc, ClipRegistrationProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public Publication lock(long clipId, long runId, int processingNo) {
        var rows = jdbc.query(
                """
            SELECT c.deleted_at, c.storage_key, active.processing_no AS active_no
            FROM npick.clip c JOIN npick.pipeline_run r ON r.clip_id=c.clip_id
            LEFT JOIN npick.pipeline_run active ON active.pipeline_run_id=c.active_pipeline_run_id
            WHERE c.clip_id=? AND r.pipeline_run_id=? AND r.processing_no=?
            FOR UPDATE OF c
            """,
                (row, index) -> new Publication(
                        new ClipPublication(
                                row.getTimestamp("deleted_at") != null, (Integer) row.getObject("active_no")),
                        row.getString("storage_key"),
                        false,
                        "none"),
                clipId,
                runId,
                processingNo);
        if (rows.isEmpty()) return null;
        var current = rows.getFirst();
        var scenes = jdbc.queryForList("""
            SELECT s.scene_id, s.start_time_ms, s.end_time_ms, s.caption_tokens, s.transcript_tokens,
                   s.transcript_source,
                   EXISTS (SELECT 1 FROM npick.keyframe k JOIN npick.ocr_observation o USING(keyframe_id)
                           WHERE k.scene_id=s.scene_id AND trim(o.tokens)<>'') AS has_ocr
            FROM npick.scene s WHERE s.clip_id=? AND s.pipeline_run_id=?
            """, clipId, runId);
        boolean ready = !scenes.isEmpty();
        boolean text = false;
        boolean provided = false;
        boolean asr = false;
        for (var scene : scenes) {
            long start = ((Number) scene.get("start_time_ms")).longValue();
            long end = ((Number) scene.get("end_time_ms")).longValue();
            List<String> keys = jdbc.query("""
                SELECT storage_key FROM npick.keyframe WHERE scene_id=? AND timestamp_ms>=? AND timestamp_ms<?
                ORDER BY keyframe_id
                """, (row, index) -> row.getString(1), scene.get("scene_id"), start, end);
            ready &= start >= 0 && end > start && !keys.isEmpty() && mediaAvailable(keys.getFirst());
            text |= nonBlank(scene.get("caption_tokens"))
                    || nonBlank(scene.get("transcript_tokens"))
                    || Boolean.TRUE.equals(scene.get("has_ocr"));
            provided |= "provided".equals(scene.get("transcript_source"));
            asr |= "asr".equals(scene.get("transcript_source"));
        }
        return new Publication(
                current.clip(), current.storageKey(), ready && text, provided ? "provided" : asr ? "asr" : "none");
    }

    public boolean mediaAvailable(String key) {
        if (key == null
                || key.isBlank()
                || key.contains("\\")
                || key.contains(":")
                || key.startsWith("/")
                || java.util.Arrays.asList(key.split("/")).contains("..")
                || properties.mediaRoot() == null) return false;
        try {
            Path root = properties.mediaRoot().toRealPath();
            Path path = root.resolve(key).normalize().toRealPath();
            return path.startsWith(root) && Files.isRegularFile(path) && Files.isReadable(path) && Files.size(path) > 0;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    public void activate(long clipId, long runId, String transcriptSource) {
        jdbc.update(
                "UPDATE npick.clip SET active_pipeline_run_id=?, transcript_source=?, updated_at=now() WHERE clip_id=?",
                runId,
                transcriptSource,
                clipId);
    }

    private static boolean nonBlank(Object value) {
        return value instanceof String text && !text.isBlank();
    }
}
