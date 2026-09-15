package com.npick.search.application.query.dense;

/** Actual per-execution settings for ranking/recording. No unmeasured production pool size is defaulted. */
public record DenseSearchSettings(String modelVersion, int poolSize) {
    public static final int DIMENSION = 1024;

    public DenseSearchSettings {
        if (!validModelVersion(modelVersion)) {
            throw new IllegalArgumentException("A known embedding model revision is required");
        }
        if (poolSize <= 0 || poolSize > 10_000) throw new IllegalArgumentException("poolSize must be in 1..10000");
    }

    /** Include this complete payload in #54's versioned search configuration and #60's record. */
    public Snapshot snapshot() {
        return new Snapshot(
                "dense-search/v1",
                modelVersion,
                poolSize,
                DIMENSION,
                "cosine",
                "distance_asc_scene_id_asc",
                "exact",
                "l2_normalize_float32");
    }

    public record Snapshot(
            String schema,
            String modelVersion,
            int poolSize,
            int dimension,
            String metric,
            String order,
            String algorithm,
            String queryPreprocessing) {}

    /** Current encoder identity is model-id@resolved HF commit, never a mutable branch or unknown revision. */
    public static boolean validModelVersion(String version) {
        return version != null && version.matches("[^\\s@]+@[0-9a-f]{40}");
    }
}
