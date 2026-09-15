package com.npick.search.application.query.dense;

/** Generated query vectors only; embedding generation and search API assembly belong to their callers. */
public interface FindDenseCandidatesQueryPort {
    DenseCandidatesResult find(DenseQuery query, DenseSearchSettings settings);
}
