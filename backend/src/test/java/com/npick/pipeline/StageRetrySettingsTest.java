package com.npick.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.npick.pipeline.domain.model.StageRetrySettings;
import com.npick.pipeline.infrastructure.config.PipelineDefinitionConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class StageRetrySettingsTest {
    @TempDir
    Path directory;

    @Test
    void permanentUnknownAndFalseReportsCannotBeEnabledByConfiguration() {
        for (String code : Set.of(
                "VALIDATION_ERROR",
                "VLM_SCHEMA_INVALID",
                "UNSUPPORTED_MEDIA",
                "INVALID_TRANSCRIPT",
                "EXTERNAL_PROCESSING_NOT_ALLOWED",
                "NO_ADAPTER",
                "UNSUPPORTED_STAGE",
                "NEW_ERROR")) {
            var settings = new StageRetrySettings(Map.of("asr", 3), Set.of(code));
            assertThat(settings.permits("asr", 1, Map.of("code", code, "retryable", true)))
                    .isFalse();
        }
        for (String code : StageRetrySettings.TRANSIENT_CODES) {
            var settings = new StageRetrySettings(Map.of("asr", 3), Set.of(code));
            assertThat(settings.permits("asr", 1, Map.of("code", code, "retryable", false)))
                    .isFalse();
            assertThat(settings.permits("asr", 1, Map.of("code", code, "retryable", true)))
                    .isTrue();
            assertThat(settings.permits("asr", 3, Map.of("code", code, "retryable", true)))
                    .isFalse();
        }
        assertThat(new StageRetrySettings(Map.of("asr", 3), Set.of())
                        .permits("asr", 1, Map.of("code", "ASR_FAILED", "retryable", true)))
                .isFalse();
    }

    @Test
    void profileSeparatesOverridesNullAndErrorAllowlist() throws Exception {
        Path profile = directory.resolve("pipeline.yml");
        Files.writeString(profile, """
                defaults: { retry_count: 2 }
                stage_overrides:
                  asr: { retry_count: 1 }
                  ocr: { retry_count: null }
                transient_errors: [ASR_FAILED]
                """);
        context(profile).run(context -> {
            assertThat(context).hasNotFailed();
            var settings = context.getBean(StageRetrySettings.class);
            assertThat(settings.attemptsFor("scene_detection")).isEqualTo(3);
            assertThat(settings.attemptsFor("asr")).isEqualTo(2);
            assertThat(settings.attemptsFor("ocr")).isEqualTo(1);
            assertThat(settings.transientErrors()).containsExactly("ASR_FAILED");
        });
        Files.writeString(profile, "defaults: { retry_count: null }");
        context(profile)
                .run(context -> assertThat(
                                context.getBean(StageRetrySettings.class).attemptsFor("asr"))
                        .isEqualTo(1));
        for (String invalid : Set.of("-1", "1.5", "true", "2147483647")) {
            Files.writeString(profile, "defaults: { retry_count: " + invalid + " }");
            context(profile).run(context -> assertThat(context).hasFailed());
        }
    }

    private ApplicationContextRunner context(Path profile) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PipelineDefinitionConfiguration.class)
                .withPropertyValues("npick.pipeline.profile=" + profile.toUri());
    }
}
