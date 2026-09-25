package com.npick.search.infrastructure.config;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredScoreConfigurationTest {
    @Test
    void bindsActualYamlAsExperimentalAndSnapshotsAllWeights() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var settings = context.getBean(StructuredScoreSettings.class);
            assertThat(settings.weightStatus()).isEqualTo(StructuredScoreSettings.WeightStatus.EXPERIMENTAL);
            assertThat(settings.weights()).hasSize(8).containsKeys(StructuredAxis.values());
        });
    }

    @Test
    void rejectsNegativeNonfiniteMissingAndOverflowWeightsAtStartup() {
        for (var value : new String[] {"-1", "NaN", "Infinity", ""}) {
            runner().withPropertyValues("npick.search.structured.weights.person=" + value)
                    .run(context -> assertThat(context).hasFailed());
        }
        runner().withPropertyValues(
                        "npick.search.structured.weights.person=1.7e308",
                        "npick.search.structured.weights.event=1.7e308")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void preservesExplicitCalibrationStatusAndConfiguredWeights() {
        runner().withPropertyValues(
                        "npick.search.structured.weight-status=calibrated",
                        "npick.search.structured.weights.person=2.5",
                        "npick.search.structured.weights.event=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var settings = context.getBean(StructuredScoreSettings.class);
                    assertThat(settings.weightStatus()).isEqualTo(StructuredScoreSettings.WeightStatus.CALIBRATED);
                    assertThat(settings.weights())
                            .containsEntry(StructuredAxis.PERSON, 2.5)
                            .containsEntry(StructuredAxis.EVENT, 0.0);
                });
    }

    @Test
    void bindsKeywordTagDefaultsFromYaml() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var keyword = context.getBean(StructuredScoreSettings.class).keyword();
            assertThat(keyword.weight()).isEqualTo(0.5);
            assertThat(keyword.conditionCap()).isEqualTo(12);
            assertThat(keyword.enabled()).isTrue();
            assertThat(keyword.stoplist()).hasSize(33).startsWith("앞", "뒤", "위").contains("북부", "인근", "장면", "보이", "관련");
        });
    }

    @Test
    void rejectsInvalidKeywordTagSettingsAtStartup() {
        for (var property : new String[] {
            "npick.search.structured.keyword.weight=-0.1",
            "npick.search.structured.keyword.weight=NaN",
            "npick.search.structured.keyword.condition-cap=-1",
            "npick.search.structured.keyword.stoplist[0]=​"
        }) {
            runner().withPropertyValues(property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void zeroKeywordWeightTurnsTheChannelOff() {
        runner().withPropertyValues("npick.search.structured.keyword.weight=0").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StructuredScoreSettings.class).keyword().enabled())
                    .isFalse();
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
                                                "structured-defaults",
                                                new FileSystemResource("src/main/resources/application.yml"))
                                        .getFirst());
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                })
                .withUserConfiguration(StructuredScoreConfiguration.class);
    }
}
