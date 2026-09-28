package com.npick.search.domain.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 실제 사용 가중치를 결과에 함께 전달한다. 전체 검색 설정의 버전 계산은 #54가 소유한다.
 *
 * @param keyword 키워드 태그 매칭 설정 (S15P21A501-321). 여기 중첩해 두어 설정 스냅샷과 교정 드리프트 가드가 따로 배선하지 않아도 본다
 */
public record StructuredScoreSettings(
        WeightStatus weightStatus, Map<StructuredAxis, Double> weights, KeywordTagSettings keyword) {
    public enum WeightStatus {
        EXPERIMENTAL,
        CALIBRATED
    }

    public StructuredScoreSettings {
        Objects.requireNonNull(weightStatus, "weightStatus");
        Objects.requireNonNull(weights, "weights");
        Objects.requireNonNull(keyword, "keyword");
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

    /** 키워드 태그 채널을 끈 설정. 321 이전 호출부(단위 테스트·순수 계산)가 쓴다. 운영 설정은 3인자로 만든다. */
    public StructuredScoreSettings(WeightStatus weightStatus, Map<StructuredAxis, Double> weights) {
        this(weightStatus, weights, KeywordTagSettings.OFF);
    }
}
