package com.npick.clip.application.query;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.clip.application.port.MediaAssetPort;
import com.npick.clip.application.query.media.ClipMediaStreamResult;
import com.npick.clip.application.query.media.StreamClipMediaQuery;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipMediaQueryServiceTest {

    private static final byte[] CONTENT = "0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String STORAGE_KEY = "clips/7/original";

    @Test
    void opensClipByIdWithoutExposingTheStorageKey() {
        ClipMediaStreamResult media = service(STORAGE_KEY, null).stream(new StreamClipMediaQuery(7, null));

        assertThat(media.totalBytes()).isEqualTo(CONTENT.length);
        assertThat(media.partial()).isFalse();
        assertThat(media.contentType()).isEqualTo("video/mp4");
        assertThat(media.internalLocation()).isNull();
    }

    /** 서비스가 구간을 결정하고 본문에 미리 묶는다. 호출자가 offset 을 다시 계산하지 않는다. */
    @Test
    void bindsTheResolvedRangeIntoTheBody() {
        ClipMediaStreamResult media = service(STORAGE_KEY, null).stream(new StreamClipMediaQuery(7, "bytes=3-5"));
        ByteArrayOutputStream target = new ByteArrayOutputStream();

        media.body().writeTo(target);

        assertThat(media.offset()).isEqualTo(3);
        assertThat(media.length()).isEqualTo(3);
        assertThat(media.partial()).isTrue();
        assertThat(target.toByteArray()).containsExactly("345".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void passesTheProxyLocationThroughWhenTheAdapterOffersOne() {
        ClipMediaStreamResult media =
                service(STORAGE_KEY, "/internal-media/clips/7/original").stream(new StreamClipMediaQuery(7, null));

        assertThat(media.internalLocation()).isEqualTo("/internal-media/clips/7/original");
    }

    /** 삭제된 영상과 없는 영상은 같은 응답을 준다. QueryPort 가 둘을 구분하지 않는다. */
    @Test
    void reportsUnknownClipBeforeTouchingTheFileSystem() {
        ClipMediaQueryService service = new ClipMediaQueryService(clipId -> Optional.empty(), storageKey -> {
            throw new AssertionError("clip 이 없으면 파일을 열지 않는다");
        });

        assertThatThrownBy(() -> service.stream(new StreamClipMediaQuery(7, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(ClipMediaErrorCode.CLIP_NOT_FOUND);
    }

    @Test
    void surfacesRangeFailureWithItsOwnCode() {
        assertThatThrownBy(() -> service(STORAGE_KEY, null).stream(new StreamClipMediaQuery(7, "bytes=999-")))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(ClipMediaErrorCode.RANGE_NOT_SATISFIABLE);
    }

    private static ClipMediaQueryService service(String storageKey, String internalLocation) {
        return new ClipMediaQueryService(
                clipId -> Optional.of(storageKey), requestedKey -> asset(requestedKey, internalLocation));
    }

    private static MediaAssetPort.MediaAsset asset(String requestedKey, String internalLocation) {
        assertThat(requestedKey).isEqualTo(STORAGE_KEY);
        return new MediaAssetPort.MediaAsset() {
            @Override
            public String contentType() {
                return "video/mp4";
            }

            @Override
            public long sizeBytes() {
                return CONTENT.length;
            }

            @Override
            public Optional<String> internalLocation() {
                return Optional.ofNullable(internalLocation);
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
