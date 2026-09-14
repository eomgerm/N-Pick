package com.npick.search.domain.policy;

import java.util.List;

/** 활성 고유 조건의 충족률과 활성 축 가중평균. 검증 상태별 가중치나 hard 제외는 없다. */
public final class StructuredScorePolicy {
    public record AxisCount(int requested, int matched, double weight) {
        public AxisCount {
            if (requested <= 0 || matched < 0 || matched > requested || !Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException("Invalid structured axis count or weight");
            }
        }

        public double score() {
            return (double) matched / requested;
        }
    }

    public double denominator(List<AxisCount> axes) {
        double sum = axes.stream().mapToDouble(AxisCount::weight).sum();
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("Non-finite structured denominator");
        return sum;
    }

    /** 가중치 합이 0인 경우도 가점 없이 0. 값이 없는 후보 축은 matched=0이며 분모에 그대로 남는다. */
    public double contribution(AxisCount axis, double denominator) {
        return denominator == 0 ? 0 : axis.score() * (axis.weight() / denominator);
    }
}
