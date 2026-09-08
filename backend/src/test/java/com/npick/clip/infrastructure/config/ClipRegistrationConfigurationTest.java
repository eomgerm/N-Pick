package com.npick.clip.infrastructure.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.npick.clip.application.port.RegistrationActorPort;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipRegistrationConfigurationTest {
    @Test
    void generatesDistinctServerIdsUsingAuthenticatedMemberAndActiveDefinition() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("actor", (RegistrationActorPort) () -> 7);
        var context = new ClipRegistrationConfiguration()
                .clipRegistrationContext(ready(), beans.getBeanProvider(RegistrationActorPort.class));
        var first = context.requireAuthorizedContext();
        var second = context.requireAuthorizedContext();
        assertThat(first.registeredById()).isEqualTo(7);
        assertThat(List.of(first.clipId(), first.pipelineRunId(), second.clipId(), second.pipelineRunId()))
                .doesNotHaveDuplicates();
        assertThat(first.pipelineVersion()).isEqualTo("test-v1");
        assertThat(first.stageNames()).containsExactly("scene_detection");
        assertThat(first.externalProcessingRequired()).isTrue();
    }

    @Test
    void refusesMissingSettingsAfterAuthentication() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("actor", (RegistrationActorPort) () -> 7);
        var settings = new ClipRegistrationProperties(null, null, null, null, null, null, null, null);
        var context = new ClipRegistrationConfiguration()
                .clipRegistrationContext(settings, beans.getBeanProvider(RegistrationActorPort.class));
        assertThatThrownBy(context::requireAuthorizedContext)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo("CLIP_503_006"));
    }

    @Test
    void bindsRegistrationDefaultsAndEnforcesFileSizeBoundary() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        var defaults = new org.springframework.boot.env.YamlPropertySourceLoader()
                                .load(
                                        "registration-defaults",
                                        new org.springframework.core.io.FileSystemResource(
                                                "src/main/resources/application.yml"))
                                .getFirst();
                        context.getEnvironment().getPropertySources().addLast(defaults);
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                })
                .withUserConfiguration(ClipRegistrationConfiguration.class)
                .withBean(tools.jackson.databind.ObjectMapper.class, tools.jackson.databind.ObjectMapper::new)
                .withBean(
                        com.npick.clip.application.command.register.RegisterClipUseCase.class,
                        () -> org.mockito.Mockito.mock(
                                com.npick.clip.application.command.register.RegisterClipUseCase.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var settings =
                            context.getBean(com.npick.clip.infrastructure.config.ClipRegistrationProperties.class);
                    assertThat(settings.inputLimits().maxFileBytes()).isEqualTo(10L * 1024 * 1024 * 1024);
                    assertThat(settings.inputLimits().maxDurationSeconds()).isEqualByComparingTo("3600");
                    assertThat(settings.probeTimeout()).isEqualTo(java.time.Duration.ofSeconds(60));
                    assertThat(settings.decodeTimeout()).isEqualTo(java.time.Duration.ofMinutes(30));
                    assertThat(settings.inputLimits().allowedContainers()).containsExactlyInAnyOrder("mp4", "mov");
                    settings.inputLimits()
                            .checkMedia("mov", new java.math.BigDecimal("3600"), List.of("h264"), List.of("aac"));
                    settings.inputLimits()
                            .checkMedia(
                                    "mp4",
                                    new java.math.BigDecimal("3600"),
                                    List.of("h264", "hevc"),
                                    List.of("aac", "mp3"));
                    settings.inputLimits()
                            .checkMedia("mp4", new java.math.BigDecimal("3600"), List.of("h264"), List.of());
                    settings.inputLimits().checkBytes(10L * 1024 * 1024 * 1024);
                    assertThatThrownBy(() -> settings.inputLimits().checkBytes(10L * 1024 * 1024 * 1024 + 1))
                            .isInstanceOf(com.npick.common.error.BusinessException.class);
                });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,true"})
    void rejectsMissingRequiredAdaptersBeforeReadingVideo(boolean hasDeduplication, boolean hasSubtitle) {
        var beans = new StaticListableBeanFactory();
        var deduplication =
                org.mockito.Mockito.mock(com.npick.clip.application.port.RegistrationDeduplicationPort.class);
        if (hasDeduplication) beans.addBean("deduplication", deduplication);
        var content = org.mockito.Mockito.mock(java.io.InputStream.class);
        var registration =
                org.mockito.Mockito.mock(com.npick.clip.application.command.register.RegisterClipUseCase.class);
        var upload = new ClipRegistrationConfiguration()
                .uploadClipUseCase(
                        ready(),
                        () -> new com.npick.clip.application.port.ClipRegistrationContextPort.Context(
                                7, 101, 201, "test-v1", List.of("scene_detection"), false),
                        registration,
                        new tools.jackson.databind.ObjectMapper(),
                        beans.getBeanProvider(com.npick.clip.application.port.RegistrationDeduplicationPort.class),
                        beans.getBeanProvider(com.npick.clip.application.port.TranscriptIntakePort.class));
        var subtitle = hasSubtitle
                ? new com.npick.clip.application.command.register.UploadClipCommand.Subtitle(
                        content, "sample.srt", "text/plain")
                : null;
        var command = new com.npick.clip.application.command.register.UploadClipCommand(
                content, "archive", null, null, null, "test-key", null, subtitle, true, false);
        assertThatThrownBy(() -> upload.upload(command))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo(
                                        com.npick.clip.application.error.ClipRuntimeErrorCode.INTEGRATION_UNAVAILABLE));
        org.mockito.Mockito.verifyNoInteractions(content, registration, deduplication);
    }

    private static ClipRegistrationProperties ready() {
        return new ClipRegistrationProperties(
                Path.of("media").toAbsolutePath(),
                Path.of("uploads").toAbsolutePath(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                "test-v1",
                List.of("scene_detection"),
                new ClipRegistrationProperties.Input(
                        1024L,
                        java.math.BigDecimal.TEN,
                        java.util.Set.of("mp4"),
                        java.util.Set.of("h264"),
                        java.util.Set.of("aac")),
                true);
    }
}
