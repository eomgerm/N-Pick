package com.npick.search.domain;

import java.util.EnumMap;

import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.policy.RrfFusionPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RrfFusionPolicyTest {
    private final RrfFusionPolicy policy = new RrfFusionPolicy();

    @Test
    void ceilingIsTheScoreOfATopHitInEveryActiveChannel() {
        var settings = settings(60, 1, 1.0, 1.0);
        // 2/(60+1). 관측 최고점이 아니라 설정에서 나오므로 질의가 달라져도 척도가 같다.
        assertThat(policy.ceiling(settings)).isCloseTo(2.0 / 61, within(1e-12));
    }

    @Test
    void offChannelsDoNotEnterTheCeilingOrContribute() {
        var settings = settings(60, 1, 1.0, 0.0);
        assertThat(policy.ceiling(settings)).isCloseTo(1.0 / 61, within(1e-12));
        assertThat(policy.contribution(settings, FusionChannel.DENSE, 1)).isZero();
    }

    @Test
    void commonScalingOfWeightsCancelsSoOnlyTheRatioMatters() {
        var unscaled = settings(60, 0.5, 1.0, 3.0);
        var scaled = settings(60, 0.5, 0.25, 0.75);
        double rankOne = 1;
        double unscaledScore = policy.baseScore(
                unscaled,
                policy.contribution(unscaled, FusionChannel.LEXICAL, (int) rankOne),
                policy.ceiling(unscaled),
                0.4);
        double scaledScore = policy.baseScore(
                scaled, policy.contribution(scaled, FusionChannel.LEXICAL, (int) rankOne), policy.ceiling(scaled), 0.4);
        // 합을 1 로 정규화해도 λ 와의 균형이 달라지지 않는다는 뜻이다.
        assertThat(unscaledScore).isCloseTo(scaledScore, within(1e-12));
    }

    @Test
    void aTopHitInEveryChannelScoresOneBeforeTheStructuredTerm() {
        var settings = settings(60, 1, 1.0, 1.0);
        double rrfSum = policy.contribution(settings, FusionChannel.LEXICAL, 1)
                + policy.contribution(settings, FusionChannel.DENSE, 1);
        assertThat(policy.baseScore(settings, rrfSum, policy.ceiling(settings), 0))
                .isCloseTo(1.0, within(1e-12));
        // λ=1 이면 구조화 만점인 태그 전용 후보가 그 값과 동점이 된다. 이기는 것이 아니라 동점이다.
        assertThat(policy.baseScore(settings, 0, policy.ceiling(settings), 1.0)).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void aFailedChannelKeepsTheCeilingSoMissingInformationDoesNotEarnPoints() {
        var settings = settings(60, 1, 1.0, 1.0);
        double ceiling = policy.ceiling(settings);
        // dense 가 죽어도 M 을 살아 있는 채널로 다시 정규화하지 않는다 (FRD F-05 「정보가 없는 항목에 가점을 주지 않는다」).
        double lexicalOnly = policy.contribution(settings, FusionChannel.LEXICAL, 1);
        assertThat(policy.baseScore(settings, lexicalOnly, ceiling, 0)).isCloseTo(0.5, within(1e-12));
        // 그 결과 구조화 항의 상대적 영향력이 커진다. 감수하는 부작용이며 λ 를 같이 줄이지 않는다 —
        // 줄이면 dense 와 무관한 태그 전용 후보까지 dense 장애의 벌을 받는다.
        assertThat(policy.baseScore(settings, lexicalOnly, ceiling, 0.6)).isCloseTo(0.5 + 0.6, within(1e-12));
    }

    private static FusionSettings settings(double k, double lambda, double lexical, double dense) {
        var weights = new EnumMap<FusionChannel, Double>(FusionChannel.class);
        weights.put(FusionChannel.LEXICAL, lexical);
        weights.put(FusionChannel.DENSE, dense);
        return new FusionSettings(k, lambda, weights, FusionSettings.WeightStatus.EXPERIMENTAL);
    }
}
