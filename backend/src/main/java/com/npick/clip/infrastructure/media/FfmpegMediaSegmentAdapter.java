package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.clip.application.port.MediaAssetPort;
import com.npick.clip.application.port.MediaSegmentPort;
import com.npick.common.error.BusinessException;

/** ffmpeg로 저장된 장면 경계를 정확히 잘라 임시 MP4를 만든다. */
public final class FfmpegMediaSegmentAdapter implements MediaSegmentPort {

    private static final MediaRootResolver.Failures FAILURES = new MediaRootResolver.Failures(
            ClipMediaErrorCode.MEDIA_ROOT_UNAVAILABLE,
            ClipMediaErrorCode.MEDIA_LOCATION_REJECTED,
            ClipMediaErrorCode.MEDIA_FILE_MISSING,
            ClipMediaErrorCode.MEDIA_READ_FAILED);

    private final MediaRootResolver paths;
    private final String executable;
    private final Duration timeout;

    public FfmpegMediaSegmentAdapter(Path mediaRoot, String executable, Duration timeout) {
        if (executable == null || executable.isBlank() || timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("ffmpeg 실행 경로와 양수인 제한 시간이 필요합니다.");
        }
        this.paths = new MediaRootResolver(mediaRoot, FAILURES);
        this.executable = executable;
        this.timeout = timeout;
    }

    @Override
    public MediaAssetPort.MediaAsset extract(String storageKey, long startTimeMs, long endTimeMs) {
        if (startTimeMs < 0 || endTimeMs <= startTimeMs) {
            throw new BusinessException(ClipMediaErrorCode.MEDIA_EXTRACTION_FAILED);
        }
        Path input = paths.resolve(storageKey).real();
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(ClipMediaErrorCode.MEDIA_FILE_MISSING);
        }

        Path output = null;
        try {
            output = Files.createTempFile("npick-scene-", ".mp4");
            Process process = start(input, output, startTimeMs, endTimeMs);
            try {
                process.getOutputStream().close();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                    throw new IOException("장면 추출 프로세스가 실패했습니다.");
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
            long size = Files.size(output);
            if (size <= 0) {
                throw new IOException("장면 추출 결과가 비어 있습니다.");
            }
            return new TemporaryMediaAsset(output, size);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            deleteQuietly(output);
            throw new BusinessException(ClipMediaErrorCode.MEDIA_EXTRACTION_FAILED, interrupted);
        } catch (IOException failure) {
            deleteQuietly(output);
            throw new BusinessException(ClipMediaErrorCode.MEDIA_EXTRACTION_FAILED, failure);
        }
    }

    private Process start(Path input, Path output, long startTimeMs, long endTimeMs) throws IOException {
        return new ProcessBuilder(
                        executable,
                        "-nostdin",
                        "-v",
                        "error",
                        "-y",
                        "-protocol_whitelist",
                        "file",
                        "-i",
                        input.toString(),
                        "-ss",
                        seconds(startTimeMs),
                        "-t",
                        seconds(endTimeMs - startTimeMs),
                        "-map",
                        "0:v:0",
                        "-map",
                        "0:a:0?",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "veryfast",
                        "-crf",
                        "20",
                        "-c:a",
                        "aac",
                        "-movflags",
                        "+faststart",
                        output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static String seconds(long milliseconds) {
        return BigDecimal.valueOf(milliseconds, 3).stripTrailingZeros().toPlainString();
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 임시 파일 정리 실패가 원래 추출 실패를 가리지 않는다.
        }
    }

    private record TemporaryMediaAsset(Path file, long sizeBytes) implements MediaAssetPort.MediaAsset {

        @Override
        public String contentType() {
            return "video/mp4";
        }

        @Override
        public Optional<String> internalLocation() {
            return Optional.empty();
        }

        @Override
        public void writeTo(OutputStream target, long offset, long count) {
            try {
                if (offset != 0 || count != sizeBytes) {
                    throw new IOException("임시 장면 파일은 전체 전송만 지원합니다.");
                }
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                    WritableByteChannel sink = Channels.newChannel(target);
                    long written = 0;
                    while (written < count) {
                        long transferred = channel.transferTo(written, count - written, sink);
                        if (transferred <= 0) break;
                        written += transferred;
                    }
                    if (written != count) throw new IOException("장면 파일 전체를 전송하지 못했습니다.");
                }
            } catch (IOException failure) {
                throw new BusinessException(ClipMediaErrorCode.MEDIA_READ_FAILED, failure);
            } finally {
                deleteQuietly(file);
            }
        }
    }
}
