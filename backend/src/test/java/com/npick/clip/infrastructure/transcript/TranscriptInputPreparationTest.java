package com.npick.clip.infrastructure.transcript;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.Command;
import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.EmbeddedInspection;
import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.EmbeddedStatus;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranscriptInputPreparationTest {
    @TempDir
    Path root;

    @Test
    void emptyInputsStillProduceReferencedEmptySnapshotAndCleanupDoesNotTouchRetainedAttempt() throws Exception {
        Files.writeString(root.resolve("video"), "video");
        var mapper = new ObjectMapper();
        var extractor = mock(MovTextExtractor.class);
        when(extractor.extract(any(), any()))
                .thenReturn(new MovTextExtractor.Extraction(
                        List.of(), new EmbeddedInspection(EmbeddedStatus.NO_TRACK, null, List.of(), false)));
        var preparation = new LocalTranscriptInputPreparation(root, 1024, new SubtitleParser(), extractor, mapper);
        var first = preparation.prepare(new Command("video", null, BigDecimal.TEN, "runs/1/transcript_selection/a1/"));
        first.retain();
        first.retain();
        first.close();
        Path retained = root.resolve(first.transcript().segmentsArtifact().storageKey());
        assertThat(mapper.readTree(Files.readAllBytes(retained))
                        .path("segments")
                        .isEmpty())
                .isTrue();
        var second = preparation.prepare(new Command("video", null, BigDecimal.TEN, "runs/1/transcript_selection/a2/"));
        second.close();
        second.close();
        assertThat(root.resolve(second.transcript().segmentsArtifact().storageKey()))
                .doesNotExist();
        assertThat(retained).exists();
        assertThat(first.transcript().embeddedInspection().broadcastCcInspected())
                .isFalse();
    }

    @Test
    void rejectsEscapingKeysAndAttemptPrefixes() throws Exception {
        Files.writeString(root.resolve("video"), "video");
        var preparation = new LocalTranscriptInputPreparation(
                root, 1024, new SubtitleParser(), mock(MovTextExtractor.class), new ObjectMapper());
        for (String key : List.of("../video", "/video", "C:/video", "C:\\video", "a/../video", "a//video")) {
            assertThatThrownBy(() -> preparation.prepare(
                            new Command(key, null, BigDecimal.TEN, "runs/1/transcript_selection/a1/")))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> preparation.prepare(new Command("video", null, BigDecimal.TEN, "runs/1/../a1/")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsSymlinkedParentWithoutReadingOrDeletingOutsideFiles() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path media = Files.createDirectory(root.resolve("media"));
        Files.writeString(outside.resolve("source"), "private content");
        try {
            Files.createSymbolicLink(media.resolve("linked"), outside);
        } catch (java.io.IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symlink privilege unavailable");
        }
        var files = new TranscriptFiles(media);
        assertThatThrownBy(() -> files.read("linked/source")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> files.write("linked/new", new byte[] {1})).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(outside.resolve("source"))).isEqualTo("private content");
    }
}
