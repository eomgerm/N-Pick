package com.npick.clip.infrastructure.transcript;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.npick.clip.application.command.register.UploadClipCommand;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscriptIntakeTest {
    @TempDir
    Path root;

    static final byte[] BYTES =
            "\uFEFF1\r\n00:00:00,010 --> 00:00:01,234\r\n한글 원본  \r\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void retainsExactRawBytesAndHashWhileClosingOnlyNewDuplicate() throws Exception {
        var adapter = adapter();
        var existing = adapter.receive(subtitle(), BigDecimal.TEN, 10);
        existing.retain();
        existing.retain();
        existing.close();
        existing.close();
        var duplicate = adapter.receive(subtitle(), BigDecimal.TEN, 10);
        assertThat(duplicate.storageKey()).isNotEqualTo(existing.storageKey()).doesNotContain("..");
        assertThat(existing.contentHash())
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)));
        assertThat(duplicate.contentHash()).isEqualTo(existing.contentHash());
        duplicate.close();
        duplicate.close();
        assertThat(root.resolve(duplicate.storageKey())).doesNotExist();
        assertThat(Files.readAllBytes(root.resolve(existing.storageKey()))).isEqualTo(BYTES);
    }

    @Test
    void rejectsEntireInvalidOrOversizedFileBeforeCreatingAnything() throws Exception {
        assertThatThrownBy(() -> adapter()
                        .receive(
                                new UploadClipCommand.Subtitle(
                                        new ByteArrayInputStream("1\ninvalid".getBytes(StandardCharsets.UTF_8)),
                                        "x.srt",
                                        "text/plain"),
                                BigDecimal.TEN,
                                10))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new LocalTranscriptIntakeAdapter(root, 2, new SubtitleParser())
                        .receive(subtitle(), BigDecimal.TEN, 10))
                .hasMessageContaining("크기 제한");
        assertThatThrownBy(() -> adapter().receive(subtitle(), new BigDecimal("1.233"), 10))
                .hasMessageContaining("line 2 (cue 1).e")
                .hasMessageContaining("영상 길이 1233 ms의 정수 ms 상한 1233 ms")
                .hasMessageContaining("1 ms 초과");
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void doesNotDeleteReplacedOrModifiedFile() throws Exception {
        var intake = adapter().receive(subtitle(), BigDecimal.TEN, 10);
        Path path = root.resolve(intake.storageKey());
        Files.writeString(path, "다른 작업자가 쓴 파일");
        assertThatThrownBy(intake::close)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo("CLIP_500_003"));
        assertThat(Files.readString(path)).isEqualTo("다른 작업자가 쓴 파일");
    }

    @Test
    void doesNotCloseCallerStream() {
        var stream = new ByteArrayInputStream(BYTES) {
            @Override
            public void close() {
                throw new AssertionError("Caller owns stream");
            }
        };
        try (var intake =
                adapter().receive(new UploadClipCommand.Subtitle(stream, "x.srt", null), BigDecimal.TEN, 10)) {
            assertThat(intake.contentHash()).hasSize(64);
        }
    }

    private LocalTranscriptIntakeAdapter adapter() {
        return new LocalTranscriptIntakeAdapter(root, 1024, new SubtitleParser());
    }

    static UploadClipCommand.Subtitle subtitle() {
        return new UploadClipCommand.Subtitle(
                new ByteArrayInputStream(BYTES), "../../client/path.srt", "application/octet-stream");
    }
}
