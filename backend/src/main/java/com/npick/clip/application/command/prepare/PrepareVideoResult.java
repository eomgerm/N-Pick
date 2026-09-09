package com.npick.clip.application.command.prepare;

import java.math.BigDecimal;
import java.util.List;

/** 검증된 임시 영상의 사용 범위. 호출자는 try-with-resources로 정리하며 실제 저장 경로는 알지 못한다. */
public interface PrepareVideoResult extends AutoCloseable {
    Metadata metadata();

    String contentHash();

    @Override
    void close();

    record Metadata(
            String container, BigDecimal durationSeconds, List<VideoStream> videoStreams, List<String> audioCodecs) {
        public Metadata {
            videoStreams = List.copyOf(videoStreams);
            audioCodecs = List.copyOf(audioCodecs);
        }
    }

    record VideoStream(String codec, int width, int height) {}
}
