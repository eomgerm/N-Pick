package com.npick.search.infrastructure.config;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

/** 실행 설정이 실제 application.yml 에서 바인딩되는지, 잘못된 값이 부팅을 멈추는지 검증한다. */
class SceneCandidateConfigurationTest {

    /** 기본값 자체는 실측 전 잠정값이지만(FRD §11), 바인딩이 깨지면 검색이 조용히 0건 나므로 값이 실제로 들어오는지는 고정한다. */
    @Test
    void bindsSceneCandidateDefaultsFromApplicationYaml() {
        runnerWithApplicationYaml().run(context -> {
            assertThat(context).hasNotFailed();
            var settings = context.getBean(SceneCandidateProperties.class);
            assertThat(settings.configVersion()).isNotBlank();
            assertThat(settings.poolSize()).isPositive();
            assertThat(settings.captionWeight()).isPositive();
            assertThat(settings.transcriptWeight()).isPositive();
            assertThat(settings.ocrWeight()).isPositive();
            assertThat(settings.isAnyFieldSearched()).isTrue();
        });
    }

    /** 후보 pool 이 0 이면 어떤 검색도 결과를 못 낸다. 질의 시점이 아니라 부팅에서 걸러야 한다. */
    @Test
    void refusesNonPositiveCandidatePool() {
        runnerWithApplicationYaml()
                .withPropertyValues("npick.search.candidate.pool-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 상한을 넘는 pool 은 설정 오타다. 한 요청이 수십만 행을 메모리로 올리는 것을 부팅에서 막는다. */
    @Test
    void refusesPoolSizeAboveSanityCap() {
        runnerWithApplicationYaml()
                .withPropertyValues("npick.search.candidate.pool-size=10001")
                .run(context -> assertThat(context).hasFailed());
        runnerWithApplicationYaml()
                .withPropertyValues("npick.search.candidate.pool-size=10000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 전 필드 가중치가 0 이면 검색 실패가 결과 0건으로 위장된다 (F-06 완료 기준). */
    @Test
    void refusesAllFieldWeightsZero() {
        runnerWithApplicationYaml()
                .withPropertyValues(
                        "npick.search.candidate.caption-weight=0",
                        "npick.search.candidate.transcript-weight=0",
                        "npick.search.candidate.ocr-weight=0")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 한 필드만 켜져 있으면 유효한 설정이다 — 대상 필드를 설정으로 좁히는 것이 요구사항이다. */
    @Test
    void acceptsSingleEnabledField() {
        runnerWithApplicationYaml()
                .withPropertyValues(
                        "npick.search.candidate.caption-weight=1.0",
                        "npick.search.candidate.transcript-weight=0",
                        "npick.search.candidate.ocr-weight=0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 음수 가중치는 매칭된 장면의 점수를 깎아 순위를 뒤집는다. 값 검증 없이 통과시키지 않는다. */
    @Test
    void refusesNegativeWeight() {
        runnerWithApplicationYaml()
                .withPropertyValues("npick.search.candidate.ocr-weight=-1.0")
                .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner runnerWithApplicationYaml() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        var defaults = new YamlPropertySourceLoader()
                                .load(
                                        "candidate-defaults",
                                        new FileSystemResource("src/main/resources/application.yml"))
                                .getFirst();
                        context.getEnvironment().getPropertySources().addLast(defaults);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                })
                .withUserConfiguration(SceneCandidateConfiguration.class);
    }
}
