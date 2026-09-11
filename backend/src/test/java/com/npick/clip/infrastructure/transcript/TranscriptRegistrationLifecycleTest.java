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

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesPrimaryFailureButKeepsSuccessfulReplayWhenSubtitleCleanupFails(
            boolean replay, org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var adapter = new LocalTranscriptIntakeAdapter(root, 1024, new SubtitleParser());
        var existing = adapter.receive(TranscriptIntakeTest.subtitle(), BigDecimal.TEN, 1);
        existing.retain();
        existing.close();
        var candidate = new AtomicReference<String>();
        var video = mock(PrepareVideoResult.class);
        when(video.metadata()).thenReturn(new PrepareVideoResult.Metadata("mp4", BigDecimal.TEN, List.of(), List.of()));
        var primary = new BusinessException(ClipRuntimeErrorCode.REGISTRATION_FAILED);
        var service = new ClipUploadService(
                () -> new ClipRegistrationContextPort.Context(1, 2, 3, "v1", List.of("transcript_selection"), false),
                command -> video,
                command -> {
                    throw primary;
                },
                (key, actor, hash, request, create) -> {
                    try {
                        Files.writeString(root.resolve(candidate.get()), "외부 변경");
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                    return replay ? new RegisterClipResult(1, 3, "queued") : create.get();
                },
                (subtitle, duration, id) -> {
                    var intake = adapter.receive(subtitle, duration, id);
                    candidate.set(intake.storageKey());
                    return intake;
                });
        if (replay) {
            assertThat(service.upload(command())).isEqualTo(new RegisterClipResult(1, 3, "queued"));
            assertThat(output.getOut())
                    .contains("Duplicate registration subtitle cleanup failed", "CLIP_500_003")
                    .doesNotContain("외부 변경", "한글 원본");
        } else {
            assertThatThrownBy(() -> service.upload(command()))
                    .isSameAs(primary)
                    .satisfies(failure -> assertThat(failure.getSuppressed())
                            .singleElement()
                            .isInstanceOfSatisfying(
                                    BusinessException.class,
                                    cleanup -> assertThat(cleanup.errorCode())
                                            .isEqualTo(
                                                    com.npick.clip.application.error.TranscriptErrorCode
                                                            .CLEANUP_FAILED)));
        }
        assertThat(Files.readString(root.resolve(candidate.get()))).isEqualTo("외부 변경");
        assertThat(Files.readAllBytes(root.resolve(existing.storageKey()))).isEqualTo(TranscriptIntakeTest.BYTES);
    }

    @Test
    void doesNotSuppressOtherResourceFailuresEvenAfterSuccessfulReplay() {
        var video = mock(PrepareVideoResult.class);
        when(video.metadata()).thenReturn(new PrepareVideoResult.Metadata("mp4", BigDecimal.TEN, List.of(), List.of()));
        var failure = new BusinessException(com.npick.clip.application.error.VideoPreparationErrorCode.CLEANUP_FAILED);
        org.mockito.Mockito.doThrow(failure).when(video).close();
        var service = new ClipUploadService(
                () -> new ClipRegistrationContextPort.Context(1, 2, 3, "v1", List.of("transcript_selection"), false),
                command -> video,
                command -> {
                    throw new AssertionError("Duplicate must not register again");
                },
                (key, actor, hash, request, create) -> new RegisterClipResult(1, 3, "queued"),
                new LocalTranscriptIntakeAdapter(root, 1024, new SubtitleParser()));
        assertThatThrownBy(() -> service.upload(command())).isSameAs(failure);
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
