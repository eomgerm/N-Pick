package com.npick.search.application.query.dense;

/** Actual per-execution settings for ranking/recording. No unmeasured production pool size is defaulted. */
public record DenseSearchSettings(String modelVersion, int poolSize, double maxDistance) {
    public static final int DIMENSION = 1024;

    /**
     * 코사인 거리의 전 범위. {@code maxDistance} 가 이 값이면 거리로는 아무것도 거르지 않는다 (S15P21A501-278).
     *
     * <p>유효성 판정({@code usable} CTE)의 상한과 같은 숫자이지만 역할이 다르다 — 그쪽은 NaN·역방향 벡터를 배제하고 커버리지를 집계하며, 이쪽은 <b>관련성 하한선</b>이다. 같이
     * 움직이게 만들면 유사도가 낮은 장면이 깨진 벡터로 집계되어 매 검색이 degraded 로 떨어진다.
     */
    public static final double FULL_COSINE_RANGE = 2.0;

    public DenseSearchSettings {
        if (!validModelVersion(modelVersion)) {
            throw new IllegalArgumentException("A known embedding model revision is required");
        }
        if (poolSize <= 0 || poolSize > 10_000) throw new IllegalArgumentException("poolSize must be in 1..10000");
        if (!(maxDistance > 0 && maxDistance <= FULL_COSINE_RANGE)) {
            throw new IllegalArgumentException("maxDistance must be in (0..2]");
        }
    }

    /** Include this complete payload in #54's versioned search configuration and #60's record. */
    public Snapshot snapshot() {
        return new Snapshot(
                "dense-search/v2",
                modelVersion,
                poolSize,
                maxDistance,
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
            double maxDistance,
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
