package com.npick.clip.infrastructure.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.npick.clip.domain.policy.VideoInputLimits;

/** 영상 등록의 저장소·입력 검사 설정. 실행 정의는 pipeline profile이 소유한다. */
@ConfigurationProperties("npick.clip-registration")
public record ClipRegistrationProperties(
        Path mediaRoot,
        Path uploadRoot,
        Duration probeTimeout,
        Duration decodeTimeout,
        Input input,
        Boolean externalProcessingRequired,

        @org.springframework.boot.context.properties.bind.DefaultValue("10485760")
        int subtitleMaxBytes) {
    public ClipRegistrationProperties {
        com.npick.clip.infrastructure.transcript.SubtitleLimits.requireValid(subtitleMaxBytes);
    }

    public void validateReady() {
        if (externalProcessingRequired == null
                || input == null
                || mediaRoot == null
                || uploadRoot == null
                || !mediaRoot.isAbsolute()
                || !uploadRoot.isAbsolute()
                || mediaRoot.normalize().equals(uploadRoot.normalize())
                || probeTimeout == null
                || probeTimeout.isZero()
                || probeTimeout.isNegative()
                || decodeTimeout == null
                || decodeTimeout.isZero()
                || decodeTimeout.isNegative()) {
            throw new IllegalArgumentException("영상 등록 실행 설정이 필요합니다.");
        }
        inputLimits();
    }

    public VideoInputLimits inputLimits() {
        if (input == null || input.maxFileBytes() == null) throw new IllegalArgumentException("영상 한도가 필요합니다.");
        return new VideoInputLimits(
                input.maxFileBytes(),
                input.maxDurationSeconds(),
                input.allowedContainers(),
                input.allowedVideoCodecs(),
                input.allowedAudioCodecs());
    }

    public record Input(
            Long maxFileBytes,
            java.math.BigDecimal maxDurationSeconds,
            java.util.Set<String> allowedContainers,
            java.util.Set<String> allowedVideoCodecs,
            java.util.Set<String> allowedAudioCodecs) {}
}
