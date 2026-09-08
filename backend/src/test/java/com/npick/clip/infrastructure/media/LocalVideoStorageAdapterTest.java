package com.npick.clip.infrastructure.media;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.npick.clip.application.command.prepare.PrepareVideoResult;
import com.npick.clip.application.error.VideoStorageErrorCode;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalVideoStorageAdapterTest {
    @TempDir
    Path directory;

    private Path media;
    private Path uploads;
    private LocalVideoInspectionAdapter inspection;
    private LocalVideoStorageAdapter storage;

    @BeforeEach
    void setUp() throws Exception {
        media = Files.createDirectory(directory.resolve("media"));
        uploads = Files.createDirectory(directory.resolve("uploads"));
        var reader = mock(FfprobeVideoReader.class);
        when(reader.readVideo(any()))
                .thenReturn(new FfprobeVideoReader.Metadata(
                        "mp4", null, List.of(new FfprobeVideoReader.VideoStream("h264", 320, 240)), List.of()));
        inspection = new LocalVideoInspectionAdapter(
                new UploadedVideoValidator(uploads, reader, mock(FfmpegVideoValidator.class)));
        storage = new LocalVideoStorageAdapter(media);
    }

    @Test
    void discardsOnlyTheOwnedOriginalAndAllowsRepeatedCleanup() throws Exception {
        try (var video = inspection.inspect(new ByteArrayInputStream(new byte[] {1}))) {
            var stored = storage.store(123, video);
            Path other = Files.createDirectories(media.resolve("clips/456")).resolve("original");
            Files.writeString(other, "existing");
            stored.discard();
            stored.discard();
            assertThat(media.resolve("clips/123")).doesNotExist();
            assertThat(Files.readString(other)).isEqualTo("existing");
        }
    }

    @Test
    void refusesToDeleteAReplacementOriginal() throws Exception {
        try (var video = inspection.inspect(new ByteArrayInputStream(new byte[] {1}))) {
            var stored = storage.store(123, video);
            Path original = media.resolve(stored.storageKey());
            Files.move(original, media.resolve("retained-original"));
            Files.writeString(original, "replacement");
            assertThatThrownBy(stored::discard).isInstanceOf(BusinessException.class);
            assertThat(Files.readString(original)).isEqualTo("replacement");
        }
    }

    @Test
    void neverOverwritesExistingVideoOrDeletesItOnCollision() throws Exception {
        try (var first = inspection.inspect(new ByteArrayInputStream(new byte[] {1}))) {
            storage.store(123, first);
        }
        try (var second = inspection.inspect(new ByteArrayInputStream(new byte[] {2}))) {
            assertThatThrownBy(() -> storage.store(123, second))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(VideoStorageErrorCode.DESTINATION_EXISTS));
        }
        assertThat(Files.readAllBytes(media.resolve("clips/123/original"))).isEqualTo(new byte[] {1});
        assertThat(uploads).isEmptyDirectory();
    }

    @Test
    void reportsInvalidStorageRootAndAllowsCallerToCleanUpload() throws Exception {
        Path unavailable = Files.writeString(directory.resolve("not-a-directory"), "occupied");
        var invalidStorage = new LocalVideoStorageAdapter(unavailable);
        try (var video = inspection.inspect(new ByteArrayInputStream(new byte[] {1}))) {
            assertThatThrownBy(() -> invalidStorage.store(123, video))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(VideoStorageErrorCode.STORAGE_FAILED);
                        assertThat(error.getMessage()).doesNotContain(directory.toString());
                    });
        }
        assertThat(uploads).isEmptyDirectory();
        assertThat(Files.readString(unavailable)).isEqualTo("occupied");
    }

    @Test
    void rejectsAlreadyClosedTemporaryVideo() {
        var video = inspection.inspect(new ByteArrayInputStream(new byte[] {1}));
        video.close();
        assertThatThrownBy(() -> storage.store(123, video)).isInstanceOf(BusinessException.class);
        assertThat(media).isEmptyDirectory();
    }

    @Test
    void onlyOneConcurrentWriterCanReserveTheSameClipLocation() throws Exception {
        try (var first = inspection.inspect(new ByteArrayInputStream(new byte[] {1}));
                var second = inspection.inspect(new ByteArrayInputStream(new byte[] {2}));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CyclicBarrier(2);
            var firstWrite = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return attempt(first);
            });
            var secondWrite = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return attempt(second);
            });
            var results = List.of(firstWrite, secondWrite);
            int successes = 0;
            for (var result : results) {
                if (result.get(10, TimeUnit.SECONDS)) successes++;
            }
            assertThat(successes).isEqualTo(1);
        }
        byte[] stored = Files.readAllBytes(media.resolve("clips/123/original"));
        assertThat(stored).hasSize(1);
        assertThat(stored[0]).isIn((byte) 1, (byte) 2);
        assertThat(uploads).isEmptyDirectory();
    }

    private boolean attempt(PrepareVideoResult video) {
        try {
            storage.store(123, video);
            return true;
        } catch (BusinessException error) {
            assertThat(error.errorCode()).isEqualTo(VideoStorageErrorCode.DESTINATION_EXISTS);
            return false;
        }
    }
}
