package com.npick.search.infrastructure.config;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;

import static org.assertj.core.api.Assertions.assertThat;

class SoftRankingConfigurationTest {

    /** 완료 조건 「boost 값을 설정으로 분리한다」. 값 자체는 실측 전 잠정값이지만(FRD §11) 바인딩이 실제로 되는지는 고정한다. */
    @Test
    void bindsActualYamlAsExperimentalAndSnapshotsEverySignal() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var settings = context.getBean(SoftRankingSettings.class);
            assertThat(settings.weightStatus()).isEqualTo(FusionSettings.WeightStatus.EXPERIMENTAL);
            assertThat(settings.weights()).hasSize(4).containsKeys(SoftSignal.values());
            assertThat(settings.tieEpsilon()).isNotNegative();
        });
    }

    /** 빠진 가중치는 오류다. 신호가 늘었는데 yml 을 안 고치면 그 신호가 조용히 꺼지므로 부팅에서 드러낸다. */
    @Test
    void rejectsNegativeNonfiniteAndBlankWeightsAtStartup() {
        for (var value : new String[] {"-1", "NaN", "Infinity", ""}) {
            runner().withPropertyValues("npick.search.soft.weights.b-roll=" + value)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void rejectsOutOfRangeTieEpsilonAtStartup() {
        for (var value : new String[] {"-1", "NaN", "Infinity", "0.1", "1000000"}) {
            runner().withPropertyValues("npick.search.soft.tie-epsilon=" + value)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    /**
     * 빈 {@code tie-epsilon} 은 오류가 아니라 0 이다.
     *
     * <p>가중치와 다르게 다루는 이유는 0 이 <b>가장 보수적인 값</b>이기 때문이다 — 보조 신호가 정확한 동점에서만 작동한다. 빠진 가중치는 신호가 조용히 꺼지는 쪽이라 드러내야 하지만, 여기는
     * 빠뜨렸을 때 도달하는 상태가 이미 안전한 기본값이다.
     */
    @Test
    void aBlankTieEpsilonFallsBackToTheMostConservativeValue() {
        runner().withPropertyValues("npick.search.soft.tie-epsilon=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SoftRankingSettings.class).tieEpsilon()).isZero();
        });
    }

    /** soft 를 전부 꺼도 부팅은 된다. 보정 없는 검색은 정상 상태이지 설정 오류가 아니다. */
    @Test
    void startsWithEverySignalTurnedOff() {
        runner().withPropertyValues(
                        "npick.search.soft.weights.recency=0",
                        "npick.search.soft.weights.b-roll=0",
                        "npick.search.soft.weights.season=0",
                        "npick.search.soft.weights.weather=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SoftRankingSettings.class).isActive(SoftSignal.B_ROLL))
                            .isFalse();
                });
    }

    @Test
    void preservesExplicitCalibrationStatusAndConfiguredValues() {
        runner().withPropertyValues(
                        "npick.search.soft.weight-status=calibrated",
                        "npick.search.soft.tie-epsilon=0.05",
                        "npick.search.soft.weights.b-roll=2.5")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var settings = context.getBean(SoftRankingSettings.class);
                    assertThat(settings.weightStatus()).isEqualTo(FusionSettings.WeightStatus.CALIBRATED);
                    assertThat(settings.tieEpsilon()).isEqualTo(0.05);
                    assertThat(settings.weightOf(SoftSignal.B_ROLL)).isEqualTo(2.5);
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        context.getEnvironment()
                                .getPropertySources()
                                .addLast(new YamlPropertySourceLoader()
                                        .load(
                                                "soft-defaults",
                                                new FileSystemResource("src/main/resources/application.yml"))
                                        .getFirst());
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                })
                .withUserConfiguration(SoftRankingConfiguration.class);
    }
}
