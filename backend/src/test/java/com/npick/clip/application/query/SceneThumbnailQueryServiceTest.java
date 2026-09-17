package com.npick.clip.application.query;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.npick.clip.application.error.SceneThumbnailErrorCode;
import com.npick.clip.application.port.ThumbnailImagePort;
import com.npick.clip.application.query.thumbnail.SceneKeyframeQueryPort;
import com.npick.clip.application.query.thumbnail.SceneKeyframeSource;
import com.npick.clip.application.query.thumbnail.SceneThumbnailResult;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SceneThumbnailQueryServiceTest {

    private static final byte[] IMAGE = "jpeg-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String STORAGE_KEY = "runs/21/frames/s0001/kf-000000000.jpg";

    @Test
    void opensTheRepresentativeKeyframeBySceneIdAlone() {
        SceneThumbnailResult thumbnail =
                service(SceneKeyframeSource.of(STORAGE_KEY)).get(30);

        assertThat(thumbnail.contentType()).isEqualTo("image/jpeg");
        assertThat(thumbnail.bytes()).isEqualTo(IMAGE);
    }

    /** 결과에 storage key 가 실릴 자리가 없다. 담을 곳이 없어야 실수로 나가지 않는다 (FRD §6.4). */
    @Test
    void keepsTheStorageKeyOutOfTheResult() {
        SceneThumbnailResult thumbnail =
                service(SceneKeyframeSource.of(STORAGE_KEY)).get(30);

        assertThat(thumbnail.toString()).doesNotContain(STORAGE_KEY);
    }

    /** 장면이 없는 것과 아직 대표 이미지가 없는 것은 화면 안내가 다르다 (FRD §6.2). */
    @Test
    void separatesAnUnknownSceneFromASceneWithoutKeyframes() {
        assertThatThrownBy(() -> service(SceneKeyframeSource.sceneMissing()).get(30))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(SceneThumbnailErrorCode.SCENE_NOT_FOUND);

        assertThatThrownBy(() -> service(SceneKeyframeSource.keyframeMissing()).get(30))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(SceneThumbnailErrorCode.KEYFRAME_NOT_FOUND);
    }

    @Test
    void doesNotTouchTheFileSystemWhenTheSceneIsUnknown() {
        SceneKeyframeQueryPort keyframes = sceneId -> SceneKeyframeSource.sceneMissing();
        ThumbnailImagePort images = storageKey -> {
            throw new AssertionError("장면이 없으면 파일을 열지 않는다");
        };

        assertThatThrownBy(() -> new SceneThumbnailQueryService(keyframes, images).get(30))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * ETag 는 바이트에서 나온다.
     *
     * <p>{@code scene_id} 나 {@code keyframe_id} 로 만들면 파일이 교체됐을 때 값이 그대로여서 브라우저가 옛 장을 계속 쓴다.
     */
    @Test
    void derivesTheEntityTagFromTheImageBytesSoAReplacedFileGetsANewOne() {
        byte[] other = "different-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

        String first = service(SceneKeyframeSource.of(STORAGE_KEY)).get(30).entityTag();
        String again = service(SceneKeyframeSource.of(STORAGE_KEY)).get(30).entityTag();
        String replaced = new SceneThumbnailQueryService(
                        sceneId -> SceneKeyframeSource.of(STORAGE_KEY),
                        storageKey -> new ThumbnailImagePort.ThumbnailImage("image/jpeg", other))
                .get(30)
                .entityTag();

        assertThat(first).isNotBlank().isEqualTo(again).isNotEqualTo(replaced);
    }

    /** Port 가 알려 온 파일 누락은 서비스가 삼키지 않고 그대로 올려 보낸다. */
    @Test
    void propagatesTheMissingFileFailureFromThePort() {
        SceneKeyframeQueryPort keyframes = sceneId -> SceneKeyframeSource.of(STORAGE_KEY);
        ThumbnailImagePort images = storageKey -> {
            throw new BusinessException(SceneThumbnailErrorCode.THUMBNAIL_FILE_MISSING);
        };

        assertThatThrownBy(() -> new SceneThumbnailQueryService(keyframes, images).get(30))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(SceneThumbnailErrorCode.THUMBNAIL_FILE_MISSING);
    }

    private static SceneThumbnailQueryService service(SceneKeyframeSource source) {
        return new SceneThumbnailQueryService(
                sceneId -> source, storageKey -> new ThumbnailImagePort.ThumbnailImage("image/jpeg", IMAGE));
    }
}
