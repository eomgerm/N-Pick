package com.npick.pipeline;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.npick.common.error.BusinessException;
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
        for (int i = 0; i < 2; i++) store.upload(PREFIX, key, bytes.length, hash, new ByteArrayInputStream(bytes));
        assertThat(store.verified(new Ref("transcript_segments", key, bytes.length, hash)))
                .isEqualTo(bytes);
        assertThatThrownBy(
                        () -> store.upload(PREFIX, key, bytes.length, "0".repeat(64), new ByteArrayInputStream(bytes)))
                .isInstanceOf(BusinessException.class);
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
            assertThatThrownBy(
                            () -> store.upload(PREFIX, key, 0, "0".repeat(64), new ByteArrayInputStream(new byte[0])))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> store.upload(
                        PREFIX, PREFIX + "short", 1, "0".repeat(64), new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(BusinessException.class);
        assertThat(root.resolve(PREFIX + "short")).doesNotExist();
    }
}
