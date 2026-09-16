package com.npick.search.domain.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 실제 사용 가중치를 결과에 함께 전달한다. 전체 검색 설정의 버전 계산은 #54가 소유한다. */
public record StructuredScoreSettings(WeightStatus weightStatus, Map<StructuredAxis, Double> weights) {
    public enum WeightStatus {
        EXPERIMENTAL,
        CALIBRATED
    }

    public StructuredScoreSettings {
        Objects.requireNonNull(weightStatus, "weightStatus");
        Objects.requireNonNull(weights, "weights");
        var copy = new EnumMap<StructuredAxis, Double>(StructuredAxis.class);
        for (var axis : StructuredAxis.values()) {
            Double weight = weights.get(axis);
            if (weight == null || !Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException(
                        "Every structured axis requires a finite nonnegative weight: " + axis);
            }
            copy.put(axis, weight);
        }
        if (!Double.isFinite(
                copy.values().stream().mapToDouble(Double::doubleValue).sum())) {
            throw new IllegalArgumentException("Structured weight sum must be finite");
        }
        weights = java.util.Collections.unmodifiableMap(copy);
    }
}
