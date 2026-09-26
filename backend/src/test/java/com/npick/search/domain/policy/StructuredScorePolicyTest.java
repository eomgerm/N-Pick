package com.npick.search.domain.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** 키워드 가산점은 개체 축 가중평균 밖의 별도 항이다 (S15P21A501-321). */
class StructuredScorePolicyTest {
    private final StructuredScorePolicy policy = new StructuredScorePolicy();

    @Test
    void keywordBonusIsMatchedRatioTimesWeight() {
        assertThat(policy.keywordBonus(3, 1, 0.5)).isCloseTo(0.5 / 3, within(1e-12));
        assertThat(policy.keywordBonus(2, 2, 0.5)).isEqualTo(0.5);
        assertThat(policy.keywordBonus(2, 0, 0.5)).isZero();
    }

    @Test
    void noQueryConditionsGiveNoBonus() {
        // 확장어 조건만 있을 때가 이 경우다 — 확장어는 점수를 받지 않는다 (S15P21A501-48).
        assertThat(policy.keywordBonus(0, 0, 0.5)).isZero();
    }

    @Test
    void rejectsImpossibleCounts() {
        assertThatThrownBy(() -> policy.keywordBonus(1, 2, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.keywordBonus(-1, 0, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.keywordBonus(1, 1, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
