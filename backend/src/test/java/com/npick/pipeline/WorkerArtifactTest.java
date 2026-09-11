package com.npick.pipeline;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.error.WorkerIntegrationErrorCode;
import com.npick.pipeline.application.port.WorkerArtifactPort.Ref;
import com.npick.pipeline.infrastructure.artifact.LocalWorkerArtifactAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerArtifactTest {
    @TempDir
    Path root;

    static final String PREFIX = "runs/1/transcript_selection/a1/";

    @Test
    void immutableUploadIntegrityAndRead() throws Exception {
        var store = new LocalWorkerArtifactAdapter(root);
        byte[] bytes = "원문".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String key = PREFIX + "segments.json";
        for (int i = 0; i < 2; i++) upload(store, key, bytes.length, hash, new ByteArrayInputStream(bytes));
        assertThat(store.verified(new Ref("transcript_segments", key, bytes.length, hash)))
                .isEqualTo(bytes);
        assertThatThrownBy(() -> upload(store, key, bytes.length, "0".repeat(64), new ByteArrayInputStream(bytes)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(WorkerIntegrationErrorCode.HASH_MISMATCH));
        assertThat(Files.readAllBytes(root.resolve(key))).isEqualTo(bytes);
    }

    @Test
    void rejectsTraversalOtherAttemptsAndTruncatedBytes() {
        var store = new LocalWorkerArtifactAdapter(root);
        for (String key : new String[] {
            PREFIX + "../escape",
            "/absolute",
            "C:/escape",
            PREFIX + "x\\y",
            "runs/2/asr/a1/x",
            "runs/1/transcript_selection/a2/x"
        }) {
            assertThatThrownBy(() -> upload(store, key, 0, "0".repeat(64), new ByteArrayInputStream(new byte[0])))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(WorkerIntegrationErrorCode.PATH_FORBIDDEN));
        }
        assertThatThrownBy(
                        () -> upload(store, PREFIX + "short", 1, "0".repeat(64), new ByteArrayInputStream(new byte[0])))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(WorkerIntegrationErrorCode.INVALID_OUTPUT));
        assertThat(root.resolve(PREFIX + "short")).doesNotExist();
    }

    private static void upload(
            LocalWorkerArtifactAdapter store, String key, long size, String hash, InputStream input) {
        try (var prepared = store.prepareUpload(PREFIX, key, size, hash, input)) {
            prepared.publish();
        }
    }

    @Test
    void cleanupFailurePreservesHashError() throws Exception {
        var store = new LocalWorkerArtifactAdapter(root);
        // Simulate cleanup I/O failure without changing the real hashing/upload path.
        try (var mocked = org.mockito.Mockito.mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("deleteIfExists"))
                throw new java.io.IOException("cleanup unavailable");
            return invocation.callRealMethod();
        })) {
            assertThatThrownBy(() ->
                            upload(store, PREFIX + "bad", 0, "0".repeat(64), new ByteArrayInputStream(new byte[0])))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(WorkerIntegrationErrorCode.HASH_MISMATCH));
            String emptyHash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
            upload(store, PREFIX + "accepted", 0, emptyHash, new ByteArrayInputStream(new byte[0]));
            assertThat(root.resolve(PREFIX + "accepted")).exists();
        }
    }

    @Test
    void acceptsLinkedRootButRejectsLinksBelowIt() throws Exception {
        Path real = Files.createDirectory(root.resolve("real"));
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (java.nio.file.FileSystemException | UnsupportedOperationException denied) {
            org.junit.jupiter.api.Assumptions.abort("OS does not permit symbolic links: " + denied.getMessage());
        }
        var store = new LocalWorkerArtifactAdapter(link);
        byte[] bytes = new byte[0];
        String hash =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        upload(store, PREFIX + "ok", 0, hash, new ByteArrayInputStream(bytes));
        assertThat(real.resolve(PREFIX + "ok")).exists();
        Files.createSymbolicLink(real.resolve(PREFIX + "escape"), root);
        assertThatThrownBy(() -> upload(store, PREFIX + "escape/file", 0, hash, new ByteArrayInputStream(bytes)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(WorkerIntegrationErrorCode.PATH_FORBIDDEN));
    }
}
