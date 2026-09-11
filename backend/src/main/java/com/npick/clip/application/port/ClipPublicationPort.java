package com.npick.clip.application.port;

import com.npick.clip.domain.model.ClipPublication;

public interface ClipPublicationPort {
    Publication lock(long clipId, long runId, int processingNo);

    boolean mediaAvailable(String storageKey);

    void activate(long clipId, long runId, String transcriptSource);

    record Publication(ClipPublication clip, String storageKey, boolean resultsReady, String transcriptSource) {}
}
