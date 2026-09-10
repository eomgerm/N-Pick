package com.npick.clip.application.port;

import java.math.BigDecimal;

public interface TranscriptPreparationSourcePort {
    Source load(long clipId, long runId);

    record Source(String videoStorageKey, String transcriptFileKey, BigDecimal videoDuration) {}
}
