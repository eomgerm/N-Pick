package com.npick.pipeline.application.port;

import java.io.InputStream;
import java.io.OutputStream;

public interface WorkerArtifactPort {
    record Ref(String kind, String storageKey, long byteSize, String contentHash) {}

    void upload(String outputKeyPrefix, String key, long size, String hash, InputStream source);

    PreparedUpload prepareUpload(String outputKeyPrefix, String key, long size, String hash, InputStream source);

    interface PreparedUpload extends AutoCloseable {
        void publish();

        void close();
    }

    void download(String key, OutputStream destination);

    byte[] verified(Ref reference);
}
