package com.npick.clip.infrastructure.transcript;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.npick.clip.application.error.TranscriptErrorCode;
import com.npick.clip.application.port.TranscriptPreparationSourcePort;
import com.npick.clip.infrastructure.config.ClipRegistrationProperties;
import com.npick.clip.infrastructure.media.FfprobeVideoReader;
import com.npick.common.error.BusinessException;

@Component
public class StoredTranscriptSourceAdapter implements TranscriptPreparationSourcePort {
    private final JdbcTemplate jdbc;
    private final ClipRegistrationProperties properties;
    private final ObjectMapper mapper;

    public StoredTranscriptSourceAdapter(
            JdbcTemplate jdbc, ClipRegistrationProperties properties, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.mapper = mapper;
    }

    public Source load(long clipId, long runId) {
        var rows = jdbc.query("""
            SELECT c.storage_key, c.transcript_file_key FROM npick.clip c
            JOIN npick.pipeline_run r ON r.clip_id=c.clip_id
            WHERE c.clip_id=? AND r.pipeline_run_id=? AND c.deleted_at IS NULL
            """, (row, index) -> new Source(row.getString(1), row.getString(2), null), clipId, runId);
        if (rows.isEmpty()) throw new BusinessException(TranscriptErrorCode.STORAGE_FAILED);
        var source = rows.getFirst();
        try {
            var file = new TranscriptFiles(properties.mediaRoot()).read(source.videoStorageKey());
            var metadata = new FfprobeVideoReader("ffprobe", properties.probeTimeout(), mapper).readVideo(file);
            return new Source(source.videoStorageKey(), source.transcriptFileKey(), metadata.durationSeconds());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new BusinessException(TranscriptErrorCode.STORAGE_FAILED, failure);
        } catch (IOException | TimeoutException | IllegalArgumentException failure) {
            throw new BusinessException(TranscriptErrorCode.STORAGE_FAILED, failure);
        }
    }
}
