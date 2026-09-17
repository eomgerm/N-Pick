package com.npick.search.domain;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SoftRankingSettingsTest {

    @Test
    void everySignalNeedsAFiniteNonNegativeWeight() {
        assertThatIllegalArgumentException().isThrownBy(() -> settings(0, -0.1, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> settings(0, Double.NaN, 1, 1, 1));
        var missing = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        missing.put(SoftSignal.B_ROLL, 1.0);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SoftRankingSettings(missing, 0, FusionSettings.WeightStatus.EXPERIMENTAL));
    }

    @Test
    void tieEpsilonMustBeFiniteAndNonNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> settings(-1e-9, 1, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> settings(Double.POSITIVE_INFINITY, 1, 1, 1, 1));
    }

    /**
     * {@code tieEpsilon} 은 <b>보조 신호가 순위를 뒤집을 수 있는 폭</b>이라 상한이 필요하다.
     *
     * <p>{@link SoftRankingSettings#MAX_TIE_EPSILON} 은 척도에서 유도한 값이 아니라 <b>임의로 고른 보수적 가드레일</b>이다. 버킷 비교의 참인 성질은 「같은
     * 버킷이면 두 {@code baseScore} 의 차가 {@code tieEpsilon} 미만」 하나뿐이고, 그 폭을 얼마까지 허용할지는 계산으로 나오지 않는다.
     */
    @Test
    void tieEpsilonIsCappedByAConservativeGuardrail() {
        assertThatIllegalArgumentException().isThrownBy(() -> settings(SoftRankingSettings.MAX_TIE_EPSILON, 1, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> settings(1e6, 1, 1, 1, 1));
        assertThat(settings(0.05, 1, 1, 1, 1).tieEpsilon()).isEqualTo(0.05);
    }

    /**
     * soft 를 전부 꺼도 검색은 성립한다. {@link FusionSettings} 가 전 채널 0 을 부팅 오류로 막는 것과 다른 판단이다 — 그쪽은 0 이면 {@code R/M} 이 정의되지
     * 않지만 여기는 보정이 없는 정상 상태다.
     */
    @Test
    void allZeroWeightsAreAllowedBecauseSoftRankingCanBeTurnedOff() {
        var off = settings(0, 0, 0, 0, 0);
        assertThat(off.isActive(SoftSignal.B_ROLL)).isFalse();
        assertThat(off.weightOf(SoftSignal.RECENCY)).isZero();
    }

    private static SoftRankingSettings settings(
            double tieEpsilon, double recency, double bRoll, double season, double weather) {
        Map<SoftSignal, Double> weights = new EnumMap<>(SoftSignal.class);
        weights.put(SoftSignal.RECENCY, recency);
        weights.put(SoftSignal.B_ROLL, bRoll);
        weights.put(SoftSignal.SEASON, season);
        weights.put(SoftSignal.WEATHER, weather);
        return new SoftRankingSettings(weights, tieEpsilon, FusionSettings.WeightStatus.EXPERIMENTAL);
    }
}
