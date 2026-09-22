package com.npick.clip.infrastructure.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FfmpegMediaSegmentAdapterIntegrationTest {

    @TempDir
    Path mediaRoot;

    @Test
    void rejectsImmediatelyWhenEveryExtractionSlotIsBusy() {
        var adapter =
                new FfmpegMediaSegmentAdapter(mediaRoot, "ffmpeg", Duration.ofSeconds(10), new Semaphore(0, true));

        assertThatThrownBy(() -> adapter.extract("clips/7/original.mp4", 0, 1_000))
                .isInstanceOf(BusinessException.class)
                .extracting(FfmpegMediaSegmentAdapterIntegrationTest::codeOf)
                .isEqualTo(ClipMediaErrorCode.MEDIA_EXTRACTION_FAILED);
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    void extractsTheStoredRangeWithOptionalAudioAndDeletesTheTemporaryAsset() throws Exception {
        Path input = mediaRoot.resolve("clips/7/original.mp4");
        Files.createDirectories(input.getParent());
        run(List.of(
                "ffmpeg",
                "-v",
                "error",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=s=160x120:r=25",
                "-f",
                "lavfi",
                "-i",
                "anullsrc=r=16000:cl=mono",
                "-t",
                "4",
                "-c:v",
                "mpeg4",
                "-c:a",
                "aac",
                input.toString()));
        var adapter = new FfmpegMediaSegmentAdapter(mediaRoot, "ffmpeg", Duration.ofSeconds(10));
        var asset = adapter.extract("clips/7/original.mp4", 1_250, 2_700);
        var bytes = new ByteArrayOutputStream();

        asset.writeTo(bytes, 0, asset.sizeBytes());

        assertThat(asset.contentType()).isEqualTo("video/mp4");
        assertThat(bytes.size()).isPositive();
        // writeTo가 끝나면 임시 파일은 이미 지워져 같은 asset을 다시 읽을 수 없다.
        assertThatThrownBy(() -> asset.writeTo(new ByteArrayOutputStream(), 0, asset.sizeBytes()))
                .isInstanceOf(BusinessException.class)
                .extracting(FfmpegMediaSegmentAdapterIntegrationTest::codeOf)
                .isEqualTo(ClipMediaErrorCode.MEDIA_READ_FAILED);

        Path extracted = mediaRoot.resolve("extracted.mp4");
        Files.write(extracted, bytes.toByteArray());
        double duration = Double.parseDouble(run(List.of(
                        "ffprobe",
                        "-v",
                        "error",
                        "-show_entries",
                        "format=duration",
                        "-of",
                        "default=noprint_wrappers=1:nokey=1",
                        extracted.toString()))
                .trim());
        assertThat(duration).isBetween(1.35, 1.60);
    }

    static boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String run(List<String> command) throws Exception {
        Path output = Files.createTempFile("npick-command-", ".out");
        Process process = new ProcessBuilder(command)
                .redirectOutput(output.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            return Files.readString(output);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
            Files.deleteIfExists(output);
        }
    }

    private static ClipMediaErrorCode codeOf(Throwable failure) {
        return (ClipMediaErrorCode) ((BusinessException) failure).errorCode();
    }
}
