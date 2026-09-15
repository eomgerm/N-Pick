package com.npick.search.application.query.dense;

/** Untrusted resolver output. Missing and malformed vectors are reported by the channel, not as empty hits. */
public record DenseQuery(float[] embedding, String modelVersion) {
    public DenseQuery {
        embedding = embedding == null ? null : embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding == null ? null : embedding.clone();
    }
}
