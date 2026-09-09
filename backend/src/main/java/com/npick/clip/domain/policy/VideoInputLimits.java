package com.npick.clip.domain.policy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.npick.clip.domain.error.VideoAdmissionErrorCode;
import com.npick.common.error.BusinessException;

/** 실행 환경이 정한 한도를 적용한다. 정책 수치를 코드에서 결정하지 않는다. */
public record VideoInputLimits(
        long maxFileBytes,
        BigDecimal maxDurationSeconds,
        Set<String> allowedContainers,
        Set<String> allowedVideoCodecs,
        Set<String> allowedAudioCodecs) {
    public VideoInputLimits {
        if (maxFileBytes <= 0
                || maxDurationSeconds == null
                || maxDurationSeconds.signum() <= 0
                || allowedContainers == null
                || allowedContainers.isEmpty()
                || allowedVideoCodecs == null
                || allowedVideoCodecs.isEmpty()
                || allowedAudioCodecs == null
                || allowedAudioCodecs.isEmpty()) {
            throw new IllegalArgumentException("양수 한도와 허용 형식·코덱 목록이 필요합니다.");
        }
        allowedContainers = Set.copyOf(allowedContainers);
        allowedVideoCodecs = Set.copyOf(allowedVideoCodecs);
        allowedAudioCodecs = Set.copyOf(allowedAudioCodecs);
        if (java.util.stream.Stream.of(allowedContainers, allowedVideoCodecs, allowedAudioCodecs)
                .flatMap(Set::stream)
                .anyMatch(value -> value.isBlank())) {
            throw new IllegalArgumentException("허용 목록에 빈 값을 넣을 수 없습니다.");
        }
    }

    public void checkBytes(long size) {
        if (size > maxFileBytes) throw new BusinessException(VideoAdmissionErrorCode.FILE_TOO_LARGE);
    }

    public void checkMedia(String container, BigDecimal duration, List<String> videoCodecs, List<String> audioCodecs) {
        if (duration == null || duration.signum() <= 0)
            throw new BusinessException(VideoAdmissionErrorCode.INVALID_DURATION);
        if (duration.compareTo(maxDurationSeconds) > 0)
            throw new BusinessException(VideoAdmissionErrorCode.VIDEO_TOO_LONG);
        if (container == null
                || Arrays.stream(container.split(",")).noneMatch(allowedContainers::contains)
                || videoCodecs.isEmpty()
                || !allowedVideoCodecs.containsAll(videoCodecs)
                || !allowedAudioCodecs.containsAll(audioCodecs)) {
            throw new BusinessException(VideoAdmissionErrorCode.UNSUPPORTED_MEDIA);
        }
    }
}
