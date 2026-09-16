package com.npick.search.domain;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;
import com.npick.search.domain.policy.SoftRankingPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

class SoftRankingPolicyTest {
    private final SoftRankingPolicy policy = new SoftRankingPolicy();

    /** 활성 신호가 하나도 없는 질의다. 「정보가 없는 항목에 가점을 주지 않는다」(F-05) 의 기본형. */
    @Test
    void aCandidateWithNoActiveSignalScoresZero() {
        assertThat(policy.softScore(Map.of(), settings(1, 1, 1, 1))).isZero();
    }

    /**
     * 활성 신호는 값이 0 이어도 분모에 남는다.
     *
     * <p>분모에서 빼면 태그가 없는 장면이 태그가 맞은 장면과 같은 점수를 받는다 — F-05 「정보가 없는 항목에 가점을 주지 않는다」가 막는 것이 정확히 이 방향이다.
     * {@code StructuredScorePolicy} 가 활성 축을 분모에 남기는 것과 같은 규칙이다.
     */
    @Test
    void anActiveSignalStaysInTheDenominatorEvenWhenTheSceneHasNoValue() {
        var values = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        values.put(SoftSignal.B_ROLL, 1.0);
        values.put(SoftSignal.SEASON, 0.0);
        assertThat(policy.softScore(values, settings(1, 1, 1, 1))).isCloseTo(0.5, within(1e-12));
    }

    /** 가중치 0 이 곧 신호 off 다. 값이 있어도 분자·분모 어느 쪽에도 들어가지 않는다. */
    @Test
    void aZeroWeightSignalLeavesBothTheNumeratorAndTheDenominator() {
        var values = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        values.put(SoftSignal.B_ROLL, 1.0);
        values.put(SoftSignal.SEASON, 0.0);
        assertThat(policy.softScore(values, settings(1, 1, 0, 1))).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void signalValuesOutsideTheUnitRangeAreRejected() {
        var tooHigh = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        tooHigh.put(SoftSignal.B_ROLL, 1.5);
        assertThatIllegalArgumentException().isThrownBy(() -> policy.softScore(tooHigh, settings(1, 1, 1, 1)));
    }

    /** epsilon 0 이면 정확히 같은 baseScore 만 동점이다. 한 ulp 라도 높으면 soft 가 끼어들 자리가 없다. */
    @Test
    void withoutEpsilonOnlyAnExactTieLetsSoftDecideTheOrder() {
        assertThat(policy.compareBase(0.5, 0.5, 0)).isZero();
        assertThat(policy.compareBase(Math.nextUp(0.5), 0.5, 0)).isPositive();
    }

    /** epsilon 안의 차이는 동점으로 본다. 그 바깥은 baseScore 가 그대로 이긴다 — soft 가 뒤집을 수 없다. */
    @Test
    void withEpsilonOnlyDifferencesInsideOneBucketCountAsATie() {
        assertThat(policy.compareBase(0.51, 0.55, 0.1)).isZero();
        assertThat(policy.compareBase(0.65, 0.55, 0.1)).isPositive();
    }

    /**
     * 버킷 비교는 추이적이다.
     *
     * <p>{@code |a-b| <= eps} 식 동점 판정을 쓰면 {@code a~b}, {@code b~c} 인데 {@code a≁c} 가 성립해 {@code Comparator} 계약이 깨지고
     * {@code List.sort} 가 {@code IllegalArgumentException} 을 던진다. 그래서 버킷 인덱스로 가른다.
     */
    @Test
    void bucketComparisonIsTransitiveUnlikeATolerantEqualityCheck() {
        double epsilon = 0.1;
        double low = 0.10;
        double middle = 0.19;
        double high = 0.28;
        // 관용 비교라면 low~middle, middle~high 인데 low≁high 라 정렬이 깨진다.
        assertThat(Math.abs(low - middle)).isLessThanOrEqualTo(epsilon);
        assertThat(Math.abs(middle - high)).isLessThanOrEqualTo(epsilon);
        assertThat(Math.abs(low - high)).isGreaterThan(epsilon);
        // 버킷은 세 값에 모순 없는 순서를 준다.
        assertThat(policy.compareBase(low, middle, epsilon)).isZero();
        assertThat(policy.compareBase(middle, high, epsilon)).isNegative();
        assertThat(policy.compareBase(low, high, epsilon)).isNegative();
    }

    private static SoftRankingSettings settings(double recency, double bRoll, double season, double weather) {
        Map<SoftSignal, Double> weights = new EnumMap<>(SoftSignal.class);
        weights.put(SoftSignal.RECENCY, recency);
        weights.put(SoftSignal.B_ROLL, bRoll);
        weights.put(SoftSignal.SEASON, season);
        weights.put(SoftSignal.WEATHER, weather);
        return new SoftRankingSettings(weights, 0, FusionSettings.WeightStatus.EXPERIMENTAL);
    }
}
