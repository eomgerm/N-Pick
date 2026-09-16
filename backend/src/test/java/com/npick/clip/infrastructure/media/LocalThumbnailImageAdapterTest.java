package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.npick.clip.application.error.SceneThumbnailErrorCode;
import com.npick.clip.application.port.ThumbnailImagePort;
import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalThumbnailImageAdapterTest {

    private static final String STORAGE_KEY = "runs/21/frames/s0001/kf-000000000.jpg";
    private static final byte[] JPEG = jpeg("keyframe");

    @TempDir
    Path root;

    private LocalThumbnailImageAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("runs/21/frames/s0001"));
        Files.write(root.resolve(STORAGE_KEY), JPEG);
        adapter = new LocalThumbnailImageAdapter(root);
    }

    @Test
    void readsTheWholeImageByStorageKey() {
        ThumbnailImagePort.ThumbnailImage image = adapter.read(STORAGE_KEY);

        assertThat(image.bytes()).isEqualTo(JPEG);
        assertThat(image.contentType()).isEqualTo("image/jpeg");
    }

    /** 확장자가 아니라 머리글로 정한다. {@code storage_key} 이름은 워커 규약일 뿐 형식의 근거가 아니다. */
    @Test
    void namesTheFormatFromTheFileHeaderNotTheExtension() throws IOException {
        Files.write(root.resolve(STORAGE_KEY), png());

        assertThat(adapter.read(STORAGE_KEY).contentType()).isEqualTo("image/png");
    }

    @Test
    void readsWebpFromItsRiffHeader() throws IOException {
        Files.write(root.resolve(STORAGE_KEY), webp());

        assertThat(adapter.read(STORAGE_KEY).contentType()).isEqualTo("image/webp");
    }

    /** 판별하지 못한 머리글을 JPEG 로 가장하지 않는다. nosniff 응답에서 잘못된 Content-Type 은 깨진 이미지만 만든다. */
    @Test
    void rejectsAnUnrecognisedImageHeader() throws IOException {
        Files.write(root.resolve(STORAGE_KEY), "not an image".getBytes(StandardCharsets.UTF_8));

        assertThatFails(() -> adapter.read(STORAGE_KEY), SceneThumbnailErrorCode.THUMBNAIL_READ_FAILED);
    }

    /** media root 밖을 가리키는 key 는 그 파일이 실제로 있어도 거부한다 (FRD §6.4). */
    @Test
    void rejectsTraversalOutsideMediaRootEvenWhenTargetExists() throws IOException {
        Path reachable = root.getParent().resolve("reachable-secret");
        Files.writeString(reachable, "top secret");
        try {
            // runs/21/frames/s0001 에서 다섯 단계를 올라가면 media root 바로 위다.
            assertThatFails(
                    () -> adapter.read("runs/21/frames/s0001/../../../../../" + reachable.getFileName()),
                    SceneThumbnailErrorCode.THUMBNAIL_LOCATION_REJECTED);
        } finally {
            Files.deleteIfExists(reachable);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "..", "../outside", "/etc/passwd"})
    void rejectsKeysThatDoNotLandInsideTheRoot(String storageKey) {
        assertThatFails(() -> adapter.read(storageKey), SceneThumbnailErrorCode.THUMBNAIL_LOCATION_REJECTED);
    }

    @Test
    void rejectsANullKey() {
        assertThatFails(() -> adapter.read(null), SceneThumbnailErrorCode.THUMBNAIL_LOCATION_REJECTED);
    }

    /** root 안을 가리키지만 링크를 따라가면 밖으로 나가는 key 도 거부한다. */
    @Test
    void rejectsASymbolicLinkThatLeavesTheRoot() throws IOException {
        Path outside = root.getParent().resolve("outside-secret.jpg");
        Files.write(outside, JPEG);
        Path link = root.resolve("runs/21/frames/s0001/kf-000000001.jpg");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("심볼릭 링크 생성 권한이 없어 건너뛴다");
        }

        assertThatFails(
                () -> adapter.read("runs/21/frames/s0001/kf-000000001.jpg"),
                SceneThumbnailErrorCode.THUMBNAIL_LOCATION_REJECTED);
    }

    @Test
    void reportsAMissingFileApartFromARejectedLocation() {
        assertThatFails(
                () -> adapter.read("runs/21/frames/s0001/kf-999999999.jpg"),
                SceneThumbnailErrorCode.THUMBNAIL_FILE_MISSING);
    }

    /** 디렉터리를 가리키는 key 는 파일이 아니다. 존재한다는 이유로 읽으려 들지 않는다. */
    @Test
    void refusesADirectory() {
        assertThatFails(() -> adapter.read("runs/21/frames/s0001"), SceneThumbnailErrorCode.THUMBNAIL_FILE_MISSING);
    }

    @Test
    void reportsAnUnavailableRootWhenItDoesNotExist() {
        LocalThumbnailImageAdapter missingRoot = new LocalThumbnailImageAdapter(root.resolve("nowhere"));

        assertThatFails(() -> missingRoot.read(STORAGE_KEY), SceneThumbnailErrorCode.MEDIA_ROOT_UNAVAILABLE);
    }

    private static void assertThatFails(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(expected);
    }

    private static byte[] jpeg(String payload) {
        return withHeader(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, payload);
    }

    private static byte[] png() {
        return withHeader(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A}, "png");
    }

    private static byte[] webp() {
        return withHeader(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, "webp");
    }

    private static byte[] withHeader(byte[] header, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] image = new byte[header.length + body.length];
        System.arraycopy(header, 0, image, 0, header.length);
        System.arraycopy(body, 0, image, header.length, body.length);
        return image;
    }
}
