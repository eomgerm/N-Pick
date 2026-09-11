package com.npick.pipeline.application.command.worker;

import java.io.InputStream;
import java.util.UUID;

public interface UploadWorkerArtifactUseCase {
    void upload(long run, String worker, UUID lease, String key, long size, String hash, InputStream input);
}
