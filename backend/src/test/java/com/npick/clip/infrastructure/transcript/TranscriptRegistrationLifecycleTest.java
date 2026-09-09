package com.npick.clip.infrastructure.transcript;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.npick.clip.application.command.ClipUploadService;
import com.npick.clip.application.command.prepare.PrepareVideoResult;
import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.UploadClipCommand;
import com.npick.clip.application.error.ClipRuntimeErrorCode;
import com.npick.clip.application.port.ClipRegistrationContextPort;
import com.npick.clip.application.port.RegistrationDeduplicationPort;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranscriptRegistrationLifecycleTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleansKnownFailureButRetainsUnknownOutcomeWithoutTouchingExistingFile(boolean unknown) throws Exception {
        var adapter = new LocalTranscriptIntakeAdapter(root, 1024, new SubtitleParser());
        var original = adapter.receive(TranscriptIntakeTest.subtitle(), BigDecimal.TEN, 1);
        original.retain();
        original.close();
        var path = new AtomicReference<String>();
        var video = mock(PrepareVideoResult.class);
        when(video.metadata()).thenReturn(new PrepareVideoResult.Metadata("mp4", BigDecimal.TEN, List.of(), List.of()));
        when(video.contentHash()).thenReturn("a".repeat(64));
        RegistrationDeduplicationPort dedup = (key, actor, hash, request, create) -> {
            assertThat(request.subtitleHash()).isEqualTo(original.contentHash());
            return create.get();
        };
        var service = new ClipUploadService(
                () -> new ClipRegistrationContextPort.Context(1, 2, 3, "v1", List.of("transcript_selection"), false),
                command -> video,
                command -> {
                    path.set(command.transcriptFileKey());
                    assertThat(command.scriptText()).isEqualTo("참고 대본");
                    throw new BusinessException(
                            unknown
                                    ? ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN
                                    : ClipRuntimeErrorCode.REGISTRATION_FAILED);
                },
                dedup,
                adapter);
        assertThatThrownBy(() -> service.upload(command())).isInstanceOf(BusinessException.class);
        assertThat(Files.exists(root.resolve(path.get()))).isEqualTo(unknown);
        assertThat(Files.readAllBytes(root.resolve(original.storageKey()))).isEqualTo(TranscriptIntakeTest.BYTES);
    }

    @Test
    void scriptOnlyDoesNotInvokeSubtitleIntakeOrGenerateSceneText() {
        var video = mock(PrepareVideoResult.class);
        var service = new ClipUploadService(
                () -> new ClipRegistrationContextPort.Context(1, 2, 3, "v1", List.of("transcript_selection"), false),
                command -> video,
                command -> {
                    assertThat(command.transcriptFileKey()).isNull();
                    assertThat(command.scriptText()).isEqualTo("참고 대본");
                    return new RegisterClipResult(2, 3, "queued");
                },
                (key, actor, hash, request, create) -> {
                    assertThat(request.subtitleHash()).isNull();
                    return create.get();
                },
                (subtitle, duration, id) -> {
                    throw new AssertionError("script is not subtitle");
                });
        var input = command();
        service.upload(
                new UploadClipCommand(input.content(), "archive", null, null, null, "key", "참고 대본", null, true, false));
    }

    private UploadClipCommand command() {
        return new UploadClipCommand(
                new ByteArrayInputStream(new byte[] {1}),
                "archive",
                null,
                null,
                null,
                "key",
                "참고 대본",
                TranscriptIntakeTest.subtitle(),
                true,
                false);
    }
}
