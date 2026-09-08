package com.npick.clip.application.command.register;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Objects;

/** 요청 스트림은 호출자가 닫는다. 등록자·ID·저장 경로는 HTTP 입력으로 받지 않는다. */
public record UploadClipCommand(
        InputStream content,
        String sourceType,
        String title,
        LocalDate broadcastDate,
        LocalDate filmedDate,
        String requestKey,
        String scriptText,
        Subtitle subtitle,
        boolean rightsConfirmed,
        boolean externalProcessingConfirmed) {
    public UploadClipCommand {
        Objects.requireNonNull(content, "content");
    }

    public record Subtitle(InputStream content, String filename, String mediaType) {
        public Subtitle {
            Objects.requireNonNull(content, "content");
        }
    }
}
