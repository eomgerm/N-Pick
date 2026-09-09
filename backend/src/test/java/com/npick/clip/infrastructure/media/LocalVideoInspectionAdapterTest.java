package com.npick.clip.infrastructure.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.npick.clip.application.error.VideoPreparationErrorCode;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalVideoInspectionAdapterTest {
    @TempDir
    Path directory;

    @Test
    void translatesUnavailableToolSeparatelyFromInvalidFile() throws Exception {
        assertTranslated(new IOException("/private/ffprobe missing"), VideoPreparationErrorCode.INSPECTION_FAILED);
    }

    @Test
    void translatesTimeoutSeparatelyFromInvalidFile() throws Exception {
        assertTranslated(new TimeoutException("timeout"), VideoPreparationErrorCode.INSPECTION_TIMED_OUT);
    }

    @Test
    void restoresInterruptFlagAtApplicationBoundary() throws Exception {
        try {
            assertTranslated(new InterruptedException("cancelled"), VideoPreparationErrorCode.INSPECTION_INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void translatesCleanupFailure() throws Exception {
        var validator = mock(UploadedVideoValidator.class);
        var video = mock(UploadedVideoValidator.ValidatedVideo.class);
        when(validator.prepare(any())).thenReturn(video);
        doThrow(new IOException("/private/temp")).when(video).close();
        var result = new LocalVideoInspectionAdapter(validator).inspect(new ByteArrayInputStream(new byte[] {1}));
        assertThatThrownBy(result::close)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(VideoPreparationErrorCode.CLEANUP_FAILED));
    }

    private void assertTranslated(Exception cause, VideoPreparationErrorCode expected) throws Exception {
        var reader = mock(FfprobeVideoReader.class);
        var decoder = mock(FfmpegVideoValidator.class);
        boolean probeFailure = cause instanceof IOException;
        if (probeFailure) {
            when(reader.readVideo(any())).thenThrow(cause);
        } else {
            doThrow(cause).when(decoder).validate(any());
        }
        var adapter = new LocalVideoInspectionAdapter(new UploadedVideoValidator(directory, reader, decoder));
        assertThatThrownBy(() -> adapter.inspect(new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(expected);
                    assertThat(failure.getMessage())
                            .isEqualTo(expected.message())
                            .doesNotContain("/private");
                    assertThat(failure.getCause()).isSameAs(cause);
                });
        assertThat(directory).isEmptyDirectory();
        if (probeFailure) org.mockito.Mockito.verifyNoInteractions(decoder);
    }
}
