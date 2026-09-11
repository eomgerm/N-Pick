package com.npick.pipeline.application.command.worker;

import java.io.OutputStream;
import java.util.UUID;

public interface DownloadWorkerArtifactUseCase {
    void download(long run, String worker, UUID lease, String key, OutputStream output);
}
