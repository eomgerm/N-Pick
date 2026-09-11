package com.npick.pipeline;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.npick.pipeline.application.port.StageOutputPort;
import com.npick.pipeline.infrastructure.config.WorkerJobSecurityConfiguration;
import com.npick.pipeline.infrastructure.config.WorkerStorageConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkerConfigurationTest {
    @TempDir
    Path root;

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class Security {}

    @Test
    void httpDisabledKeepsStorageAndIgnoresInactiveShortToken() {
        context().withPropertyValues("npick.worker-jobs.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(StageOutputPort.class);
            assertThat(context.getBean(StageOutputPort.class).supports("transcript_selection"))
                    .isTrue();
            assertThat(context.getBean(StageOutputPort.class).supports("ocr")).isFalse();
        });
    }

    @Test
    void httpEnabledStillRejectsShortToken() {
        context()
                .withPropertyValues("npick.worker-jobs.enabled=true")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void noMediaRootDoesNotPreventStartupOrWriteIntoWorkingDirectory() {
        context()
                .withPropertyValues("npick.worker-jobs.enabled=false", "npick.clip-registration.media-root=")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(StageOutputPort.class);
                    var artifacts = context.getBean(com.npick.pipeline.application.port.WorkerArtifactPort.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(
                                    () -> artifacts.download("clips/1/source.mp4", new java.io.ByteArrayOutputStream()))
                            .isInstanceOfSatisfying(
                                    com.npick.common.error.BusinessException.class,
                                    e -> assertThat(e.errorCode().code()).isEqualTo("JOB_503_001"));
                });
    }

    private WebApplicationContextRunner context() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(
                        Security.class, WorkerJobSecurityConfiguration.class, WorkerStorageConfiguration.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, JsonMapper::new)
                .withPropertyValues("npick.worker-jobs.tokens=short", "npick.clip-registration.media-root=" + root);
    }
}
