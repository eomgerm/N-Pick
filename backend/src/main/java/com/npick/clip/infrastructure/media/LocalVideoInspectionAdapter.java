package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

import com.npick.clip.application.command.prepare.PrepareVideoResult;
import com.npick.clip.application.error.VideoPreparationErrorCode;
import com.npick.clip.application.port.VideoInspectionPort;
import com.npick.common.error.BusinessException;

public final class LocalVideoInspectionAdapter implements VideoInspectionPort {
    private final UploadedVideoValidator validator;

    public LocalVideoInspectionAdapter(UploadedVideoValidator validator) {
        this.validator = validator;
    }

    @Override
    public PrepareVideoResult inspect(InputStream content) {
        try {
            var video = validator.prepare(content);
            return new LocalPreparedVideo(video);
        } catch (InvalidVideoFileException exception) {
            throw new BusinessException(VideoPreparationErrorCode.INVALID_VIDEO, exception);
        } catch (TimeoutException exception) {
            throw new BusinessException(VideoPreparationErrorCode.INSPECTION_TIMED_OUT, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(VideoPreparationErrorCode.INSPECTION_INTERRUPTED, exception);
        } catch (IOException exception) {
            throw new BusinessException(VideoPreparationErrorCode.INSPECTION_FAILED, exception);
        }
    }

    static final class LocalPreparedVideo implements PrepareVideoResult {
        private final UploadedVideoValidator.ValidatedVideo video;

        private LocalPreparedVideo(UploadedVideoValidator.ValidatedVideo video) {
            this.video = video;
        }

        Path temporaryPath() {
            return video.path();
        }

        @Override
        public String contentHash() {
            return video.contentHash();
        }

        @Override
        public Metadata metadata() {
            var metadata = video.metadata();
            return new Metadata(
                    metadata.container(),
                    metadata.durationSeconds(),
                    metadata.videoStreams().stream()
                            .map(stream -> new VideoStream(stream.codec(), stream.width(), stream.height()))
                            .toList(),
                    metadata.audioCodecs());
        }

        @Override
        public void close() {
            try {
                video.close();
            } catch (IOException exception) {
                throw new BusinessException(VideoPreparationErrorCode.CLEANUP_FAILED, exception);
            }
        }
    }
}
