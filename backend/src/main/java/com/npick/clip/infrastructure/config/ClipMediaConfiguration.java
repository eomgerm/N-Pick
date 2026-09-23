package com.npick.clip.infrastructure.config;

import java.util.concurrent.Semaphore;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.npick.clip.application.port.MediaAssetPort;
import com.npick.clip.application.port.MediaSegmentPort;
import com.npick.clip.infrastructure.media.FfmpegMediaSegmentAdapter;
import com.npick.clip.infrastructure.media.LocalMediaAssetAdapter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClipMediaProperties.class)
public class ClipMediaConfiguration {

    /** media root 검증을 요청 시점으로 미룬다. 설정이 비어도 기동은 되고 재생 요청만 503 이 된다. */
    @Bean
    MediaAssetPort mediaAssetPort(ClipMediaProperties properties) {
        return storageKey -> new LocalMediaAssetAdapter(
                        properties.requireMediaRoot(), properties.internalLocationPrefix())
                .resolve(storageKey);
    }

    @Bean
    MediaSegmentPort mediaSegmentPort(ClipMediaProperties properties) {
        Semaphore extractionSlots = new Semaphore(properties.maxConcurrentExtractions(), true);
        return (storageKey, startTimeMs, endTimeMs) -> new FfmpegMediaSegmentAdapter(
                        properties.requireMediaRoot(), "ffmpeg", properties.extractionTimeout(), extractionSlots)
                .extract(storageKey, startTimeMs, endTimeMs);
    }
}
