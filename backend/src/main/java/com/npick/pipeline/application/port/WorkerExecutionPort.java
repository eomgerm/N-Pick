package com.npick.pipeline.application.port;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Transport integration point. Its implementation delegates to #36; never implements another executor. */
public interface WorkerExecutionPort {
    Map<String, Object> claim(String workerId, Map<String, Object> request);

    Map<String, Object> heartbeat(long runId, String stage, String workerId, Map<String, Object> request);

    Map<String, Object> complete(long runId, String stage, String workerId, String key, Map<String, Object> result);

    /** Must fence worker+lease+expiry and protect the operation against concurrent reassignment. */
    <T> T withArtifactAccess(long runId, String workerId, UUID leaseId, Function<ArtifactAccess, T> operation);

    /** Read keys come only from this assignment's stored media/upstream/prepared input references. */
    record ArtifactAccess(String outputKeyPrefix, Set<String> readableKeys) {
        public ArtifactAccess {
            readableKeys = Set.copyOf(readableKeys);
        }
    }
}
