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

    /**
     * 키워드 태그 가산점 (S15P21A501-321). {@link #denominator} 에 넣지 않는 별도 항이다.
     *
     * <p>가중평균에 넣으면 분모가 커져 개체 축만 맞은 장면의 점수가 깎인다(서울역 장소 태그 장면 1.0 → 0.67). 키워드는 VLM 추정이 대부분이라 개체보다 불확실하므로 낮은 가중치로 더하기만
     * 한다.
     *
     * @param requested 질의 명사로 만든 키워드 조건 수. 확장어 조건은 세지 않는다
     * @param matched 그중 유효 키워드 태그와 맞은 수. 같은 조건에 태그가 여럿이어도 한 번이다
     */
    public double keywordBonus(int requested, int matched, double weight) {
        if (requested < 0 || matched < 0 || matched > requested || !Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException("Invalid keyword count or weight");
        }
        return requested == 0 ? 0 : (double) matched / requested * weight;
    }
}
