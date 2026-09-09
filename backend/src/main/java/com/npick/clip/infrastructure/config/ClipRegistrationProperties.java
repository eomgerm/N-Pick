package com.npick.clip.infrastructure.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.npick.clip.domain.policy.VideoInputLimits;

/** npick.clip-registration. 단계 목록은 배포하는 worker의 활성 실행 정의를 전달한다. */
@ConfigurationProperties("npick.clip-registration")
public record ClipRegistrationProperties(
        Path mediaRoot,
        Path uploadRoot,
        Duration probeTimeout,
        Duration decodeTimeout,
        String pipelineVersion,
        List<String> stageNames,
        Input input,
        Boolean externalProcessingRequired) {
    public ClipRegistrationProperties {
        stageNames = stageNames == null ? List.of() : List.copyOf(stageNames);
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
                || decodeTimeout.isNegative()
                || pipelineVersion == null
                || pipelineVersion.isBlank()
                || pipelineVersion.length() > 128
                || stageNames.isEmpty()
                || stageNames.stream().anyMatch(s -> s == null || s.isBlank())
                || stageNames.stream().distinct().count() != stageNames.size()) {
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
