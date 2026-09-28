package com.npick.pipeline.application.port;

import java.util.UUID;

public interface PreparationLeasePort {
    Guard maintain(long runId, String stage, String workerId, UUID leaseId);

    interface Guard extends AutoCloseable {
        void verify();

        void close();
    }
}
