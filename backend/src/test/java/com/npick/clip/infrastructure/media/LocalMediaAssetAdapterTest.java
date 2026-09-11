package com.npick.clip.infrastructure.media;

import java.io.ByteArrayOutputStream;
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

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.clip.application.port.MediaAssetPort;
import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalMediaAssetAdapterTest {

    private static final String STORAGE_KEY = "clips/42/original";
    private static final byte[] CONTENT = "0123456789".repeat(10).getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private LocalMediaAssetAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("clips/42"));
        Files.write(root.resolve(STORAGE_KEY), CONTENT);
        adapter = new LocalMediaAssetAdapter(root, null);
    }

    @Test
    void opensStoredOriginalByStorageKey() {
        MediaAssetPort.MediaAsset asset = adapter.resolve(STORAGE_KEY);

        assertThat(asset.sizeBytes()).isEqualTo(CONTENT.length);
        assertThat(asset.internalLocation()).isEmpty();
    }

    @Test
    void writesOnlyTheRequestedWindow() {
        ByteArrayOutputStream target = new ByteArrayOutputStream();

        adapter.resolve(STORAGE_KEY).writeTo(target, 10, 5);

        assertThat(target.toByteArray()).containsExactly("01234".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void keepsResponseStreamOpenAfterWriting() throws IOException {
        Path sink = outside.resolve("sink");
        try (var stream = Files.newOutputStream(sink)) {
            adapter.resolve(STORAGE_KEY).writeTo(stream, 0, 4);
            // 채널을 닫아 버리면 이어지는 쓰기가 실패한다. 응답 스트림을 재사용해야 하므로 열려 있어야 한다.
            stream.write('!');
        }
        assertThat(Files.readAllBytes(sink)).containsExactly("0123!".getBytes(StandardCharsets.UTF_8));
    }

    /** media root 밖을 가리키는 key 는 그 파일이 실제로 있어도 거부한다 (FR-ING-009 연계). */
    @Test
    void rejectsTraversalOutsideMediaRootEvenWhenTargetExists() throws IOException {
        Path reachable = root.getParent().resolve("reachable-secret");
        Files.writeString(reachable, "top secret");
        try {
            // clips/42 에서 세 단계를 올라가면 media root 바로 위다.
            assertThatThrownBy(() -> adapter.resolve("clips/42/../../../" + reachable.getFileName()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(LocalMediaAssetAdapterTest::codeOf)
                    .isEqualTo(ClipMediaErrorCode.MEDIA_LOCATION_REJECTED);
        } finally {
            Files.deleteIfExists(reachable);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"../outside", "clips/42/../../../etc/passwd", "clips/../..", "..", "./", " ", "/etc/passwd"})
    void rejectsKeysThatEscapeOrDoNotAddressAFile(String storageKey) {
        assertThatThrownBy(() -> adapter.resolve(storageKey))
                .isInstanceOf(BusinessException.class)
                .extracting(LocalMediaAssetAdapterTest::codeOf)
                .isEqualTo(ClipMediaErrorCode.MEDIA_LOCATION_REJECTED);
    }

    @Test
    void rejectsSymbolicLinkPointingOutsideMediaRoot() throws IOException {
        Path secret = outside.resolve("secret");
        Files.writeString(secret, "top secret");
        Path link = root.resolve("clips/42/escape");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException unsupported) {
            // Windows 는 심볼릭 링크 생성에 권한이 필요하다. 만들 수 없으면 검증할 것도 없다.
            Assumptions.abort("심볼릭 링크를 만들 수 없는 환경입니다: " + unsupported.getMessage());
        }

        assertThatThrownBy(() -> adapter.resolve("clips/42/escape"))
                .isInstanceOf(BusinessException.class)
                .extracting(LocalMediaAssetAdapterTest::codeOf)
                .isEqualTo(ClipMediaErrorCode.MEDIA_LOCATION_REJECTED);
    }

    @Test
    void reportsMissingFileSeparatelyFromRejectedLocation() {
        assertThatThrownBy(() -> adapter.resolve("clips/999/original"))
                .isInstanceOf(BusinessException.class)
                .extracting(LocalMediaAssetAdapterTest::codeOf)
                .isEqualTo(ClipMediaErrorCode.MEDIA_FILE_MISSING);
    }

    @Test
    void reportsDirectoryAsMissingFile() {
        assertThatThrownBy(() -> adapter.resolve("clips/42"))
                .isInstanceOf(BusinessException.class)
                .extracting(LocalMediaAssetAdapterTest::codeOf)
                .isEqualTo(ClipMediaErrorCode.MEDIA_FILE_MISSING);
    }

    @Test
    void reportsUnavailableMediaRoot() {
        LocalMediaAssetAdapter missingRoot = new LocalMediaAssetAdapter(root.resolve("nowhere"), null);

        assertThatThrownBy(() -> missingRoot.resolve(STORAGE_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(LocalMediaAssetAdapterTest::codeOf)
                .isEqualTo(ClipMediaErrorCode.MEDIA_ROOT_UNAVAILABLE);
    }

    @Test
    void readsContainerFromFileHeaderBecauseOriginalHasNoExtension() throws IOException {
        Files.write(root.resolve(STORAGE_KEY), isoBaseMediaFile("qt  "));
        assertThat(adapter.resolve(STORAGE_KEY).contentType()).isEqualTo("video/quicktime");

        Files.write(root.resolve(STORAGE_KEY), isoBaseMediaFile("isom"));
        assertThat(adapter.resolve(STORAGE_KEY).contentType()).isEqualTo("video/mp4");

        // ftyp 이 없거나 너무 짧으면 mp4 로 본다.
        Files.write(root.resolve(STORAGE_KEY), CONTENT);
        assertThat(adapter.resolve(STORAGE_KEY).contentType()).isEqualTo("video/mp4");
        Files.write(root.resolve(STORAGE_KEY), new byte[] {1, 2});
        assertThat(adapter.resolve(STORAGE_KEY).contentType()).isEqualTo("video/mp4");
    }

    @Test
    void handsProxyARelativeInternalLocationOnly() {
        MediaAssetPort.MediaAsset asset = new LocalMediaAssetAdapter(root, "/internal-media/").resolve(STORAGE_KEY);

        assertThat(asset.internalLocation()).contains("/internal-media/clips/42/original");
        // 서버 절대 경로가 헤더로 나가지 않는다 (FR-RES-013).
        assertThat(asset.internalLocation().orElseThrow()).doesNotContain(root.toString());
    }

    @Test
    void escapesInternalLocationForTheProxy() throws IOException {
        Files.createDirectories(root.resolve("clips/a b"));
        Files.write(root.resolve("clips/a b/original"), CONTENT);

        assertThat(new LocalMediaAssetAdapter(root, "/internal-media/")
                        .resolve("clips/a b/original")
                        .internalLocation())
                .contains("/internal-media/clips/a%20b/original");
    }

    private static byte[] isoBaseMediaFile(String brand) {
        byte[] header = new byte[32];
        header[3] = 32;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, header, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, header, 8, 4);
        return header;
    }

    private static ErrorCode codeOf(Throwable failure) {
        return ((BusinessException) failure).errorCode();
    }
}
