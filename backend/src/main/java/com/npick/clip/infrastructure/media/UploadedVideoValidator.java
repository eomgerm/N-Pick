package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeoutException;

import com.npick.clip.domain.policy.VideoInputLimits;

/** 업로드 내용을 임시 파일로 검사한다. 영구 저장은 수행하지 않는다. */
public final class UploadedVideoValidator {
    private final Path temporaryDirectory;
    private final FfprobeVideoReader reader;
    private final FfmpegVideoValidator decoder;
    private final VideoInputLimits limits;

    public UploadedVideoValidator(Path temporaryDirectory, FfprobeVideoReader reader, FfmpegVideoValidator decoder) {
        this(temporaryDirectory, reader, decoder, null);
    }

    public UploadedVideoValidator(
            Path temporaryDirectory, FfprobeVideoReader reader, FfmpegVideoValidator decoder, VideoInputLimits limits) {
        this.temporaryDirectory = temporaryDirectory;
        this.reader = reader;
        this.decoder = decoder;
        this.limits = limits;
    }

    /** 검사 결과만 필요한 경우 사용한다. 반환 전에 임시 파일을 정리한다. 입력 스트림은 호출자가 닫는다. */
    public FfprobeVideoReader.Metadata validate(InputStream upload)
            throws IOException, InterruptedException, TimeoutException {
        try (var video = prepare(upload)) {
            return video.metadata();
        }
    }

    /** 검증한 임시 파일의 정리 책임을 호출자에게 넘긴다. 반환값은 try-with-resources로 사용한다. 입력 스트림은 호출자가 닫으며, 임시 파일 경로에는 원본 파일명을 사용하지 않는다. */
    public ValidatedVideo prepare(InputStream upload) throws IOException, InterruptedException, TimeoutException {
        MessageDigest digest = sha256();
        Path temporary = Files.createTempFile(temporaryDirectory, "npick-upload-", ".bin");
        try {
            try (var output = new DigestOutputStream(Files.newOutputStream(temporary), digest)) {
                byte[] buffer = new byte[8192];
                long size = 0;
                int read;
                while ((read = upload.read(buffer)) != -1) {
                    size = Math.addExact(size, read);
                    if (limits != null) limits.checkBytes(size);
                    output.write(buffer, 0, read);
                }
            }
            var metadata = reader.readVideo(temporary);
            if (limits != null)
                limits.checkMedia(
                        metadata.container(),
                        metadata.durationSeconds(),
                        metadata.videoStreams().stream()
                                .map(FfprobeVideoReader.VideoStream::codec)
                                .toList(),
                        metadata.audioCodecs());
            decoder.validate(temporary);
            return new ValidatedVideo(temporary, metadata, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | InterruptedException | TimeoutException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("실행 환경에서 SHA-256을 지원해야 합니다.", exception);
        }
    }

    public static final class ValidatedVideo implements AutoCloseable {
        private final Path path;
        private final FfprobeVideoReader.Metadata metadata;
        private final String contentHash;

        private ValidatedVideo(Path path, FfprobeVideoReader.Metadata metadata, String contentHash) {
            this.path = path;
            this.metadata = metadata;
            this.contentHash = contentHash;
        }

        public Path path() {
            return path;
        }

        public FfprobeVideoReader.Metadata metadata() {
            return metadata;
        }

        public String contentHash() {
            return contentHash;
        }

        /** 임시 경로만 정리한다. 파일이 다른 위치로 이동했다면 이동한 파일은 삭제하지 않는다. */
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
