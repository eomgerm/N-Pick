package com.npick.search.application.query.dense;

import com.npick.search.application.query.dense.DenseCandidatesResult.Reason;

/** Validation of generated vectors, before touching a DB transaction. Does not generate or re-embed text. */
public final class DenseQueryValidation {
    private DenseQueryValidation() {}

    public static Reason validate(DenseQuery query, DenseSearchSettings settings) {
        float[] vector = query.embedding();
        if (vector == null) return Reason.QUERY_VECTOR_MISSING;
        if (vector.length != DenseSearchSettings.DIMENSION) return Reason.QUERY_DIMENSION_MISMATCH;
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) return Reason.QUERY_VECTOR_INVALID;
            norm += (double) value * value;
        }
        if (norm == 0) return Reason.QUERY_VECTOR_INVALID;
        if (!DenseSearchSettings.validModelVersion(query.modelVersion())) {
            return Reason.QUERY_MODEL_MISSING;
        }
        return settings.modelVersion().equals(query.modelVersion()) ? Reason.NONE : Reason.QUERY_MODEL_MISMATCH;
    }
}
