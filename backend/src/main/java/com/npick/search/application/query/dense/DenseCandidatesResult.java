package com.npick.search.application.query.dense;

import java.util.List;

/** Channel-local status; only the search assembler can decide whether basic search remains available. */
public record DenseCandidatesResult(
        Status status,
        Reason reason,
        List<Candidate> candidates,
        DenseSearchSettings.Snapshot settings,
        String queryModelVersion,
        Coverage coverage) {
    public DenseCandidatesResult {
        candidates = List.copyOf(candidates);
    }

    public enum Status {
        AVAILABLE,
        PARTIAL,
        UNAVAILABLE
    }

    public enum Reason {
        NONE,
        QUERY_VECTOR_MISSING,
        QUERY_DIMENSION_MISMATCH,
        QUERY_VECTOR_INVALID,
        QUERY_MODEL_MISSING,
        QUERY_MODEL_MISMATCH,
        STORED_VECTOR_UNAVAILABLE,
        DENSE_QUERY_FAILED
    }

    /** Rank is one-based, similarity = 1 - cosine distance; neither is an RRF contribution. */
    public record Candidate(
            long sceneId,
            long clipId,
            long pipelineRunId,
            int rank,
            double distance,
            double similarity,
            String modelVersion) {}

    /**
     * Null coverage means the DB query did not complete. Missing vectors are counted separately from corrupt metadata.
     */
    public record Coverage(
            long eligible,
            long missingVectors,
            long unusableVectors,
            long usableVectors,
            long unavailableStages,
            long missingModels,
            long modelMismatches,
            long invalidVectors) {}

    public static DenseCandidatesResult unavailable(Reason reason, DenseQuery query, DenseSearchSettings settings) {
        return new DenseCandidatesResult(
                Status.UNAVAILABLE, reason, List.of(), settings.snapshot(), query.modelVersion(), null);
    }
}
