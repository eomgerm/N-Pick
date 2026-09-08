package com.npick.clip.infrastructure.media;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UploadedVideoValidatorTest {
    @TempDir
    Path directory;

    private final FfprobeVideoReader reader = mock(FfprobeVideoReader.class);
    private final FfmpegVideoValidator decoder = mock(FfmpegVideoValidator.class);

    @Test
    void rejectsActualByteOverflowBeforeProbingAndDeletesTemporaryFile() {
        var limits = new com.npick.clip.domain.policy.VideoInputLimits(
                2,
                java.math.BigDecimal.TEN,
                java.util.Set.of("mp4"),
                java.util.Set.of("h264"),
                java.util.Set.of("aac"));
        var validator = new UploadedVideoValidator(directory, reader, decoder, limits);
        assertThatThrownBy(() -> validator.prepare(new ByteArrayInputStream(new byte[] {1, 2, 3})))
                .isInstanceOfSatisfying(
                        com.npick.common.error.BusinessException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo("CLIP_400_005"));
        verifyNoInteractions(reader, decoder);
        assertThat(directory).isEmptyDirectory();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}: {5}")
    @org.junit.jupiter.params.provider.CsvSource({
        "too-long, mp4, 11, h264, aac, CLIP_400_006",
        "missing-duration, mp4, , h264, aac, CLIP_400_007",
        "zero-duration, mp4, 0, h264, aac, CLIP_400_007",
        "container, matroska, 10, h264, aac, CLIP_400_008",
        "video-codec, mp4, 10, vp9, aac, CLIP_400_008",
        "audio-codec, mp4, 10, h264, opus, CLIP_400_008"
    })
    void appliesDurationAndCodecLimitsBeforeFullDecoding(
            String scenario,
            String container,
            java.math.BigDecimal duration,
            String videoCodec,
            String audioCodec,
            String expectedCode)
            throws Exception {
        var limits = new com.npick.clip.domain.policy.VideoInputLimits(
                10,
                java.math.BigDecimal.TEN,
                java.util.Set.of("mp4"),
                java.util.Set.of("h264"),
                java.util.Set.of("aac"));
        var validator = new UploadedVideoValidator(directory, reader, decoder, limits);
        when(reader.readVideo(any()))
                .thenReturn(new FfprobeVideoReader.Metadata(
                        container,
                        duration,
                        List.of(new FfprobeVideoReader.VideoStream(videoCodec, 320, 240)),
                        List.of(audioCodec)));
        assertThatThrownBy(() -> validator.prepare(new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOfSatisfying(
                        com.npick.common.error.BusinessException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo(expectedCode));
        verifyNoInteractions(decoder);
        assertThat(directory).isEmptyDirectory();
    }

    @Test
    void computesKnownSha256RegardlessOfInputChunkSize() throws Exception {
        byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        var validator = new UploadedVideoValidator(directory, reader, decoder);
        try (var whole = validator.prepare(new ByteArrayInputStream(bytes));
                var chunks = new FilterInputStream(new ByteArrayInputStream(bytes)) {
                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        return super.read(buffer, offset, Math.min(length, 1));
                    }
                };
                var chunked = validator.prepare(chunks)) {
            assertThat(whole.contentHash()).isEqualTo(expected);
            assertThat(chunked.contentHash()).isEqualTo(expected);
            assertThat(Files.readAllBytes(chunked.path())).isEqualTo(bytes);
        }
        assertThat(directory).isEmptyDirectory();
    }

    @Test
    void keepsValidatedBytesAndMetadataAvailableUntilCallerFinishes() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        var metadata = new FfprobeVideoReader.Metadata(
                "mp4", null, List.of(new FfprobeVideoReader.VideoStream("h264", 320, 240)), List.of());
        when(reader.readVideo(any())).thenAnswer(call -> {
            assertThat(Files.readAllBytes(call.getArgument(0))).isEqualTo(bytes);
            return metadata;
        });
        var validator = new UploadedVideoValidator(directory, reader, decoder);
        Path temporary;

        try (var video = validator.prepare(new ByteArrayInputStream(bytes))) {
            temporary = video.path();
            assertThat(temporary.getParent()).isEqualTo(directory);
            assertThat(Files.readAllBytes(temporary)).isEqualTo(bytes);
            assertThat(video.metadata()).isSameAs(metadata);
            var order = inOrder(reader, decoder);
            order.verify(reader).readVideo(temporary);
            order.verify(decoder).validate(temporary);
        }

        assertThat(temporary).doesNotExist();
        assertThat(directory).isEmptyDirectory();
    }

    @Test
    void deletesPartialUploadWhenCopyFails() throws Exception {
        var validator = new UploadedVideoValidator(directory, reader, decoder);
        try (InputStream broken = new InputStream() {
            private boolean first = true;

            @Override
            public int read() throws IOException {
                if (first) {
                    first = false;
                    return 1;
                }
                throw new IOException("upload interrupted");
            }
        }) {
            assertThatThrownBy(() -> validator.validate(broken))
                    .isInstanceOf(IOException.class)
                    .hasMessage("upload interrupted");
        }
        assertThat(directory).isEmptyDirectory();
        verifyNoInteractions(reader, decoder);
    }
}
