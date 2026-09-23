package com.npick.clip.application.query;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.clip.application.port.MediaAssetPort;
import com.npick.clip.application.query.media.SceneMediaSource;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipMediaDownloadServiceTest {

    private static final byte[] CONTENT = "download".getBytes(StandardCharsets.UTF_8);

    @Test
    void downloadsTheWholeOriginalByClipId() {
        var service = new ClipMediaDownloadService(
                clipId -> Optional.of("clips/7/original"),
                sceneId -> Optional.empty(),
                key -> asset("video/quicktime"),
                (key, start, end) -> {
                    throw new AssertionError("원본 다운로드에서 장면 추출을 호출하면 안 됩니다.");
                });

        var result = service.downloadClip(7);

        assertThat(result.fileName()).isEqualTo("clip-7.mov");
        assertThat(result.contentType()).isEqualTo("video/quicktime");
        assertThat(body(result.body())).containsExactly(CONTENT);
    }

    @Test
    void extractsOnlyTheStoredSceneRange() {
        var service = new ClipMediaDownloadService(
                clipId -> Optional.empty(),
                sceneId -> Optional.of(new SceneMediaSource("clips/7/original", 1_250, 2_700)),
                key -> {
                    throw new AssertionError("장면 다운로드에서 원본 전체 asset을 열면 안 됩니다.");
                },
                (key, start, end) -> {
                    assertThat(key).isEqualTo("clips/7/original");
                    assertThat(start).isEqualTo(1_250);
                    assertThat(end).isEqualTo(2_700);
                    return asset("video/mp4");
                });

        var result = service.downloadScene(99);

        assertThat(result.fileName()).isEqualTo("scene-99.mp4");
        assertThat(body(result.body())).containsExactly(CONTENT);
    }

    @Test
    void distinguishesMissingSceneFromAnExtractionFailure() {
        var missing = new ClipMediaDownloadService(
                clipId -> Optional.empty(),
                sceneId -> Optional.empty(),
                key -> asset("video/mp4"),
                (key, start, end) -> asset("video/mp4"));
        var invalid = new ClipMediaDownloadService(
                clipId -> Optional.empty(),
                sceneId -> Optional.of(new SceneMediaSource("clips/7/original", 5_000, 5_000)),
                key -> asset("video/mp4"),
                (key, start, end) -> asset("video/mp4"));

        assertThatThrownBy(() -> missing.downloadScene(99))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(ClipMediaErrorCode.SCENE_NOT_FOUND);
        assertThatThrownBy(() -> invalid.downloadScene(99))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(ClipMediaErrorCode.MEDIA_EXTRACTION_FAILED);
    }

    private static byte[] body(com.npick.clip.application.query.media.ClipMediaBody body) {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        body.writeTo(target);
        return target.toByteArray();
    }

    private static MediaAssetPort.MediaAsset asset(String contentType) {
        return new MediaAssetPort.MediaAsset() {
            @Override
            public String contentType() {
                return contentType;
            }

            @Override
            public long sizeBytes() {
                return CONTENT.length;
            }

            @Override
            public Optional<String> internalLocation() {
                return Optional.empty();
            }

            @Override
            public void writeTo(OutputStream target, long offset, long count) {
                try {
                    target.write(CONTENT, (int) offset, (int) count);
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
    }
}
