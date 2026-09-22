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

    /**
     * 확장어 문서빈도 컷이 범위 밖이면 부팅이 막히고, 메시지가 설정 키를 이름으로 부른다 (S15P21A501-302).
     *
     * <p>0 이하면 확장어가 전부 잘려 동의어 검색(F-04 가 여기로 위임한 유일한 경로)이 통째로 죽고, 1 을 넘으면 어떤 문서빈도도 넘지 못해 설정이 아무 일도 하지 않는다. 둘 다 검색이 조용히
     * 달라지는 오설정이라 질의 시점이 아니라 부팅에서 막는다 — dense 의 {@code max-distance} 와 같은 이유다 (S15P21A501-278).
     */
    @Test
    void refusesExpandedTermMaxDfOutOfRange() {
        assertThat(failureOf("npick.search.candidate.expanded-term-max-df=0"))
                .contains("npick.search.candidate.expanded-term-max-df");
        assertThat(failureOf("npick.search.candidate.expanded-term-max-df=1.01"))
                .contains("npick.search.candidate.expanded-term-max-df");
        assertThat(failureOf("npick.search.candidate.expanded-term-max-df=-0.1"))
                .contains("npick.search.candidate.expanded-term-max-df");
    }

    /** 경계값은 유효하다. 1.0 은 «아무것도 버리지 않는다» 는 뜻이지 오설정이 아니다. */
    @Test
    void acceptsExpandedTermMaxDfAtBounds() {
        runnerWithApplicationYaml()
                .withPropertyValues("npick.search.candidate.expanded-term-max-df=1.0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 기본값은 실측으로 정한 골짜기다 (2026-09-22 운영 DB, 활성 장면 1029건). 바인딩이 깨지면 컷이 조용히 사라진다. */
    @Test
    void bindsExpandedTermMaxDfFromApplicationYaml() {
        runnerWithApplicationYaml().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SceneCandidateProperties.class).expandedTermMaxDf())
                    .isEqualTo(0.06);
        });
    }

    private static String failureOf(String property) {
        var message = new StringBuilder();
        runnerWithApplicationYaml().withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            message.append(rootMessages(context.getStartupFailure()));
        });
        return message.toString();
    }

    private static String rootMessages(Throwable failure) {
        var joined = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            joined.append(cause.getMessage()).append(System.lineSeparator());
        }
        return joined.toString();
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
