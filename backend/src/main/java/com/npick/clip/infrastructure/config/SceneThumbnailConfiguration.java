package com.npick.clip.infrastructure.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.npick.clip.application.error.SceneThumbnailErrorCode;
import com.npick.clip.application.port.ThumbnailImagePort;
import com.npick.clip.infrastructure.media.LocalThumbnailImageAdapter;
import com.npick.common.error.BusinessException;

/**
 * 장면 대표 이미지 조회 배선.
 *
 * <p>media root 를 {@link ClipMediaProperties} 에서 읽는다. 물리적으로 한 위치이고(워커가 keyframe 을 영상과 같은 root 아래 남긴다), 썸네일에는 재생의
 * {@code nginx-accel} 같은 자기 전용 선택지가 없어 네 번째 설정 접두를 만들 이유가 없다. 다만 설정 누락을 알리는 어휘는 썸네일의 것을 쓴다 — 재생 오류 코드가 썸네일 응답에 나가면 화면이
 * 어느 기능이 막혔는지 구분하지 못한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClipMediaProperties.class)
public class SceneThumbnailConfiguration {

    /** media root 검증을 요청 시점으로 미룬다. 설정이 비어도 기동은 되고 썸네일 요청만 503 이 된다. */
    @Bean
    ThumbnailImagePort thumbnailImagePort(ClipMediaProperties properties) {
        return storageKey -> new LocalThumbnailImageAdapter(mediaRoot(properties)).read(storageKey);
    }

    private static Path mediaRoot(ClipMediaProperties properties) {
        Path root = properties.mediaRoot();
        if (root == null || !root.isAbsolute()) {
            throw new BusinessException(SceneThumbnailErrorCode.MEDIA_ROOT_UNAVAILABLE);
        }
        return root;
    }
}
