package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.npick.clip.application.error.SceneThumbnailErrorCode;
import com.npick.clip.application.port.ThumbnailImagePort;
import com.npick.common.error.BusinessException;

/**
 * media root 안에서만 대표 이미지를 읽는다 (FRD F-03·§6.4).
 *
 * <p>keyframe 파일은 파이프라인 워커가 영상 원본과 같은 media root 아래에 남긴다. 그래서 경로 이탈 차단 규칙은 재생과 같은 {@link MediaRootResolver} 를 쓰고, 실패
 * 어휘와 형식 판별만 썸네일의 것이다. 서버 절대 경로는 이 클래스 밖으로 나가지 않는다.
 */
public final class LocalThumbnailImageAdapter implements ThumbnailImagePort {

    private static final String JPEG_CONTENT_TYPE = "image/jpeg";
    private static final String PNG_CONTENT_TYPE = "image/png";
    private static final String WEBP_CONTENT_TYPE = "image/webp";

    /**
     * 한 장짜리 keyframe 이 이보다 크면 파이프라인이 대표 이미지가 아닌 것을 남긴 것이다.
     *
     * <p>바이트를 통째로 읽기 때문에 상한이 없으면 잘못 들어온 파일 하나가 힙을 가져간다. 1080p JPEG 는 보통 1MB 를 넘지 않으므로 여유를 크게 두고도 보호가 된다.
     */
    private static final long MAX_IMAGE_BYTES = 16L * 1024 * 1024;

    private static final MediaRootResolver.Failures FAILURES = new MediaRootResolver.Failures(
            SceneThumbnailErrorCode.MEDIA_ROOT_UNAVAILABLE,
            SceneThumbnailErrorCode.THUMBNAIL_LOCATION_REJECTED,
            SceneThumbnailErrorCode.THUMBNAIL_FILE_MISSING,
            SceneThumbnailErrorCode.THUMBNAIL_READ_FAILED);

    private final MediaRootResolver paths;

    public LocalThumbnailImageAdapter(Path mediaRoot) {
        this.paths = new MediaRootResolver(mediaRoot, FAILURES);
    }

    @Override
    public ThumbnailImage read(String storageKey) {
        Path file = paths.resolve(storageKey).real();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(SceneThumbnailErrorCode.THUMBNAIL_FILE_MISSING);
        }
        byte[] bytes = readAll(file);
        return new ThumbnailImage(contentType(bytes), bytes);
    }

    private static byte[] readAll(Path file) {
        try {
            if (Files.size(file) > MAX_IMAGE_BYTES) {
                throw new BusinessException(SceneThumbnailErrorCode.THUMBNAIL_READ_FAILED);
            }
            return Files.readAllBytes(file);
        } catch (IOException failure) {
            throw new BusinessException(SceneThumbnailErrorCode.THUMBNAIL_READ_FAILED, failure);
        }
    }

    /**
     * 파일 머리글로 형식을 정한다.
     *
     * <p>확장자를 믿지 않는 이유는 {@code storage_key} 가 워커가 정한 이름이고 DB 에 형식 칸이 없기 때문이다. 현재 워커는 JPEG 만 남기지만(frame_extraction 의
     * {@code FILE_NAME_TEMPLATE}), 형식이 바뀌어도 응답 Content-Type 이 조용히 틀리지 않게 머리글을 읽는다. 판별하지 못하면 JPEG 로 본다 — 응답에는
     * {@code X-Content-Type-Options: nosniff} 가 함께 나가므로 브라우저가 이 값을 넘겨 다시 추측하지 않는다.
     */
    private static String contentType(byte[] image) {
        if (startsWith(image, 0xFF, 0xD8, 0xFF)) {
            return JPEG_CONTENT_TYPE;
        }
        if (startsWith(image, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG_CONTENT_TYPE;
        }
        if (startsWith(image, 'R', 'I', 'F', 'F') && image.length >= 12 && isWebp(image)) {
            return WEBP_CONTENT_TYPE;
        }
        return JPEG_CONTENT_TYPE;
    }

    private static boolean isWebp(byte[] image) {
        return image[8] == 'W' && image[9] == 'E' && image[10] == 'B' && image[11] == 'P';
    }

    private static boolean startsWith(byte[] image, int... magic) {
        if (image.length < magic.length) {
            return false;
        }
        for (int index = 0; index < magic.length; index++) {
            if (image[index] != (byte) magic[index]) {
                return false;
            }
        }
        return true;
    }
}
