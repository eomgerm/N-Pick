package com.npick.search.domain;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FusionSettingsTest {

    @Test
    void allZeroChannelWeightsAreRejectedBecauseTheCeilingWouldBeZero() {
        // M=0 이면 R/M 이 정의되지 않는다. 부팅은 되는데 순위만 조용히 구조화 단독이 되는 상태를 만들지 않는다.
        assertThatThrownBy(() -> settings(0.0, 0.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroWeightOnOneChannelMeansThatChannelIsOff() {
        var settings = settings(1.0, 0.0);
        assertThat(settings.isActive(FusionChannel.LEXICAL)).isTrue();
        // 별도 enabled 플래그를 두지 않는다 — 두 값이 어긋난 상태를 만들지 않기 위해서다.
        assertThat(settings.isActive(FusionChannel.DENSE)).isFalse();
    }

    @Test
    void negativeOrNonFiniteSettingsAreRejected() {
        assertThatThrownBy(() -> settings(-1.0, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FusionSettings(-1, 0, weights(1.0, 1.0), FusionSettings.WeightStatus.EXPERIMENTAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new FusionSettings(60, Double.NaN, weights(1.0, 1.0), FusionSettings.WeightStatus.EXPERIMENTAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyChannelNeedsAWeightSoANewChannelCannotBeSilentlyIgnored() {
        assertThatThrownBy(() -> new FusionSettings(
                        60, 1, Map.of(FusionChannel.LEXICAL, 1.0), FusionSettings.WeightStatus.EXPERIMENTAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lambdaAboveOneIsAllowedBecauseItIsAProductPolicyNotAnFrdInvariant() {
        // 「구조화만으로 전 채널 1등을 이기지 않게 한다」는 선택할 수 있는 정책이다. FRD 가 요구하는 경계가 아니라 코드로 막지 않는다.
        assertThat(new FusionSettings(60, 2.5, weights(1.0, 1.0), FusionSettings.WeightStatus.EXPERIMENTAL).lambda())
                .isEqualTo(2.5);
    }

    private static FusionSettings settings(double lexical, double dense) {
        return new FusionSettings(60, 1, weights(lexical, dense), FusionSettings.WeightStatus.EXPERIMENTAL);
    }

    private static Map<FusionChannel, Double> weights(double lexical, double dense) {
        var weights = new EnumMap<FusionChannel, Double>(FusionChannel.class);
        weights.put(FusionChannel.LEXICAL, lexical);
        weights.put(FusionChannel.DENSE, dense);
        return weights;
    }
}
