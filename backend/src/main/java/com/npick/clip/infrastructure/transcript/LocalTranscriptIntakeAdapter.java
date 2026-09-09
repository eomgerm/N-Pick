package com.npick.clip.infrastructure.transcript;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import com.npick.clip.application.command.register.UploadClipCommand;
import com.npick.clip.application.error.TranscriptErrorCode;
import com.npick.clip.application.port.TranscriptIntakePort;
import com.npick.common.error.BusinessException;

public final class LocalTranscriptIntakeAdapter implements TranscriptIntakePort {
    private final Path mediaRoot;
    private final int maxBytes;
    private final SubtitleParser parser;

    public LocalTranscriptIntakeAdapter(Path mediaRoot, int maxBytes, SubtitleParser parser) {
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) throw new IllegalArgumentException("자막 크기 제한이 필요합니다.");
        this.mediaRoot = mediaRoot;
        this.maxBytes = maxBytes;
        this.parser = parser;
    }

    public Intake receive(UploadClipCommand.Subtitle subtitle, BigDecimal videoDuration, long clipId) {
        if (clipId <= 0) throw new BusinessException(TranscriptErrorCode.STORAGE_FAILED);
        String format = parser.format(subtitle.filename());
        try {
            // MIME은 클라이언트 힌트다. 확장자로 파서를 고르고 실제 전체 내용을 검사한다.
            byte[] bytes = TranscriptFiles.bounded(subtitle.content(), maxBytes);
            parser.parse(bytes, format, videoDuration);
            var file = new TranscriptFiles(mediaRoot)
                    .write("transcripts/" + clipId + "/" + UUID.randomUUID() + "." + format, bytes);
            return new Intake() {
                public String storageKey() {
                    return file.key;
                }

                public String contentHash() {
                    return file.hash;
                }

                public void retain() {
                    file.retain();
                }

                public void close() {
                    file.close();
                }
            };
        } catch (IOException failure) {
            throw new BusinessException(TranscriptErrorCode.STORAGE_FAILED, failure);
        }
    }
}
