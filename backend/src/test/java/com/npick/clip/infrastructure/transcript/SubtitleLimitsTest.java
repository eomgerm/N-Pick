package com.npick.clip.infrastructure.transcript;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.npick.clip.infrastructure.config.ClipRegistrationProperties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubtitleLimitsTest {
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, Integer.MAX_VALUE})
    void rejectsInvalidConfigurationAndDirectConstructionBeforeIo(int limit) {
        var source = new MapConfigurationPropertySource(Map.of("npick.clip-registration.subtitle-max-bytes", limit));
        assertThatThrownBy(() -> new Binder(source)
                        .bind("npick.clip-registration", Bindable.of(ClipRegistrationProperties.class)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalTranscriptIntakeAdapter(Path.of("unused"), limit, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalTranscriptInputPreparation(Path.of("unused"), limit, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MovTextExtractor(null, null, null, Duration.ofSeconds(1), limit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new SubtitleProcess().run(java.util.List.of("must-not-start"), Duration.ofSeconds(1), limit))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
