package com.npick.clip.infrastructure.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.common.error.BusinessException;

/**
 * npick.clip-media. Preview 재생 설정.
 *
 * <p>{@code media-root} 는 등록({@code npick.clip-registration.media-root})과 같은 위치를 가리켜야 한다. 두 설정이 같은 환경 변수를 같은 순서로 읽으므로
 * 실제 환경에서는 항상 같은 값이 된다. 접두를 나눈 이유는 재생이 등록 실행 설정(ffprobe 시간 제한·단계 목록 등)의 완결성에 묶이지 않게 하려는 것이다 — 등록 설정이 비어 있어도 이미 저장된 영상은
 * 재생되어야 한다.
 *
 * @param nginxAccel true 면 바이트 전송을 프록시의 internal location 에 위임한다
 * @param internalLocation 프록시 위임 대상 접두. nginx 의 {@code location /internal-media/} 와 같아야 한다
 */
@ConfigurationProperties("npick.clip-media")
public record ClipMediaProperties(
        Path mediaRoot,
        boolean nginxAccel,
        String internalLocation,
        @DefaultValue("2m") Duration extractionTimeout) {

    private static final String DEFAULT_INTERNAL_LOCATION = "/internal-media/";

    public ClipMediaProperties {
        internalLocation =
                internalLocation == null || internalLocation.isBlank() ? DEFAULT_INTERNAL_LOCATION : internalLocation;
        if (extractionTimeout == null || extractionTimeout.isZero() || extractionTimeout.isNegative()) {
            throw new IllegalArgumentException("장면 추출 제한 시간은 양수여야 합니다.");
        }
    }

    /** 기동은 설정 없이도 되게 하고, 재생 요청 시점에만 막는다. 등록 설정과 같은 방식이다. */
    public Path requireMediaRoot() {
        if (mediaRoot == null || !mediaRoot.isAbsolute()) {
            throw new BusinessException(ClipMediaErrorCode.MEDIA_ROOT_UNAVAILABLE);
        }
        return mediaRoot;
    }

    /** 직접 전송이면 {@code null}. */
    public String internalLocationPrefix() {
        return nginxAccel ? internalLocation : null;
    }
}
