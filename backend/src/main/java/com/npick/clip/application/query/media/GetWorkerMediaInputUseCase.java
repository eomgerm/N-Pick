package com.npick.clip.application.query.media;

/** Internal worker input lookup by the clip ID stored in the assigned run. */
public interface GetWorkerMediaInputUseCase {
    MediaInput get(long clipId);

    record MediaInput(String storageKey, long sizeBytes) {}
}
