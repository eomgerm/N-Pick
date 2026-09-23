package com.npick.clip.presentation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.npick.clip.application.command.ClipUploadService;
import com.npick.clip.application.command.StoredClipRegistrationService;
import com.npick.clip.application.command.VideoPreparationService;
import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.RegisterClipUseCase;
import com.npick.clip.application.command.register.RegistrationOutcome;
import com.npick.clip.application.command.register.UploadClipUseCase;
import com.npick.clip.application.port.ClipRegistrationContextPort;
import com.npick.clip.infrastructure.media.FfmpegVideoValidator;
import com.npick.clip.infrastructure.media.FfprobeVideoReader;
import com.npick.clip.infrastructure.media.LocalVideoInspectionAdapter;
import com.npick.clip.infrastructure.media.LocalVideoStorageAdapter;
import com.npick.clip.infrastructure.media.UploadedVideoValidator;
import com.npick.clip.presentation.controller.ClipRegistrationController;
import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClipRegistrationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ErrorTypeHttpStatusMapper.class)
class ClipUploadFlowTest {
    @TempDir
    Path directory;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UploadClipUseCase useCase;

    Path uploads;
    Path media;
    FfprobeVideoReader probe;
    RegisterClipUseCase database;
    ClipRegistrationContextPort context;
    com.npick.clip.application.port.TranscriptIntakePort transcripts;
    com.npick.clip.application.port.RegistrationDeduplicationPort deduplication;

    @BeforeEach
    void setup() throws Exception {
        uploads = Files.createDirectory(directory.resolve("uploads"));
        media = Files.createDirectory(directory.resolve("media"));
        probe = mock(FfprobeVideoReader.class);
        when(probe.readVideo(any()))
                .thenReturn(new FfprobeVideoReader.Metadata(
                        "mp4", null, List.of(new FfprobeVideoReader.VideoStream("h264", 320, 240)), List.of()));
        database = mock(RegisterClipUseCase.class);
        transcripts = mock(com.npick.clip.application.port.TranscriptIntakePort.class);
        deduplication = mock(com.npick.clip.application.port.RegistrationDeduplicationPort.class);
        when(deduplication.register(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(4)).get());
        context = mock(ClipRegistrationContextPort.class);
        when(context.requireAuthorizedContext())
                .thenReturn(new ClipRegistrationContextPort.Context(
                        3, 101, 201, "test-v1", List.of("scene_detection"), false));
        var flow = new ClipUploadService(
                context,
                new VideoPreparationService(new LocalVideoInspectionAdapter(
                        new UploadedVideoValidator(uploads, probe, mock(FfmpegVideoValidator.class)))),
                new StoredClipRegistrationService(new LocalVideoStorageAdapter(media), database),
                deduplication,
                transcripts);
        when(useCase.upload(any())).thenAnswer(call -> flow.upload(call.getArgument(0)));
    }

    @Test
    void forwardsSubtitleAndScriptAndRetainsOnlyCommittedIntake() throws Exception {
        var intake = mock(com.npick.clip.application.port.TranscriptIntakePort.Intake.class);
        when(intake.storageKey()).thenReturn("transcripts/101/provided.srt");
        when(intake.contentHash()).thenReturn("b".repeat(64));
        when(transcripts.receive(any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(101L)))
                .thenReturn(intake);
        when(database.register(any())).thenAnswer(call -> {
            com.npick.clip.application.command.register.RegisterClipCommand command = call.getArgument(0);
            assertThat(command.transcriptFileKey()).isEqualTo("transcripts/101/provided.srt");
            assertThat(command.scriptText()).isEqualTo("영상 전체 참고 대본");
            return new RegisterClipResult(
                    command.clipId(), command.pipelineRunId(), "queued", RegistrationOutcome.CREATED);
        });
        mvc.perform(multipart("/api/v1/clips")
                        .header("Idempotency-Key", "subtitle-request")
                        .file(upload())
                        .file(new MockMultipartFile("subtitle", "provided.srt", "text/plain", new byte[] {1}))
                        .param("source_type", "archive")
                        .param("rights_confirmed", "true")
                        .param("script_text", "영상 전체 참고 대본"))
                .andExpect(status().isCreated());
        var order = org.mockito.Mockito.inOrder(intake);
        order.verify(intake).retain();
        order.verify(intake).close();
    }

    @Test
    void returnsDeduplicationResultWithoutCreatingAnotherFileOrDatabaseRecord() throws Exception {
        var intake = mock(com.npick.clip.application.port.TranscriptIntakePort.Intake.class);
        when(intake.contentHash()).thenReturn("b".repeat(64));
        when(transcripts.receive(any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(101L)))
                .thenReturn(intake);
        org.mockito.Mockito.doReturn(new RegisterClipResult(900, 901, "queued", RegistrationOutcome.CREATED))
                .when(deduplication)
                .register(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
        mvc.perform(multipart("/api/v1/clips")
                        .header("Idempotency-Key", "existing-request")
                        .file(upload())
                        .file(new MockMultipartFile("subtitle", "provided.srt", "text/plain", new byte[] {1}))
                        .param("source_type", "archive")
                        .param("rights_confirmed", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.clip_id").value("900"));
        verifyNoInteractions(database);
        org.mockito.Mockito.verify(intake, org.mockito.Mockito.never()).retain();
        org.mockito.Mockito.verify(intake).close();
        assertThat(media).isEmptyDirectory();
        assertThat(uploads).isEmptyDirectory();
    }

    @Test
    void rejectsMissingRightsBeforeInspectingVideo() throws Exception {
        mvc.perform(multipart("/api/v1/clips")
                        .header("Idempotency-Key", "unchecked")
                        .file(upload())
                        .param("source_type", "archive"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLIP_400_009"));
        verifyNoInteractions(probe, database);
    }

    @Test
    void mapsMultipartToRegistrationWithServerValuesAndClosesTemporaryVideo() throws Exception {
        when(database.register(any())).thenAnswer(invocation -> {
            com.npick.clip.application.command.register.RegisterClipCommand command = invocation.getArgument(0);
            assertThat(command.registeredById()).isEqualTo(3);
            assertThat(command.clipId()).isEqualTo(101);
            assertThat(command.pipelineRunId()).isEqualTo(201);
            assertThat(command.broadcastDate()).isNull();
            assertThat(command.filmedDate()).hasToString("2026-09-08");
            assertThat(command.storageKey()).isEqualTo("clips/101/original");
            assertThat(command.contentHash()).hasSize(64);
            return new RegisterClipResult(
                    command.clipId(), command.pipelineRunId(), "queued", RegistrationOutcome.CREATED);
        });
        mvc.perform(multipart("/api/v1/clips")
                        .header("Idempotency-Key", "test-key")
                        .param("rights_confirmed", "true")
                        .file(upload())
                        .param("source_type", "archive")
                        .param("filmed_date", "2026-09-08")
                        .param("registered_by_id", "999")
                        .param("clip_id", "999")
                        .param("storage_key", "../outside"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.clip_id").value("101"))
                .andExpect(jsonPath("$.data.pipeline_run_id").value("201"))
                .andExpect(jsonPath("$.data.status").value("queued"))
                .andExpect(jsonPath("$.data.outcome").value("created"));
        assertThat(Files.readAllBytes(media.resolve("clips/101/original"))).containsExactly((byte) 1, (byte) 2);
        assertThat(uploads).isEmptyDirectory();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"registration", "unknown-outcome", "subtitle-intake"})
    void cleansFailedUploadAndRetainsFilesOnlyForUnknownOutcome(String scenario) throws Exception {
        var intake = mock(com.npick.clip.application.port.TranscriptIntakePort.Intake.class);
        when(intake.storageKey()).thenReturn("transcripts/101/provided.srt");
        when(intake.contentHash()).thenReturn("b".repeat(64));
        boolean unknown = scenario.equals("unknown-outcome");
        if (scenario.equals("subtitle-intake")) {
            when(transcripts.receive(
                            any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(101L)))
                    .thenThrow(new IllegalStateException("intake failed"));
        } else {
            when(transcripts.receive(
                            any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(101L)))
                    .thenReturn(intake);
            RuntimeException failure = unknown
                    ? new BusinessException(
                            com.npick.clip.application.error.ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN)
                    : new IllegalStateException("database failed");
            when(database.register(any())).thenThrow(failure);
        }
        mvc.perform(multipart("/api/v1/clips")
                        .header("Idempotency-Key", "test-key")
                        .param("rights_confirmed", "true")
                        .file(upload())
                        .file(new MockMultipartFile("subtitle", "provided.srt", "text/plain", new byte[] {1}))
                        .param("source_type", "archive"))
                .andExpect(status().is(unknown ? 503 : 500));
        if (unknown) assertThat(media.resolve("clips/101/original")).exists();
        else assertThat(media.resolve("clips/101")).doesNotExist();
        if (scenario.equals("subtitle-intake")) org.mockito.Mockito.verifyNoInteractions(database, intake);
        else {
            org.mockito.Mockito.verify(intake, org.mockito.Mockito.times(unknown ? 1 : 0))
                    .retain();
            org.mockito.Mockito.verify(intake).close();
        }
        assertThat(uploads).isEmptyDirectory();
    }

    @Test
    void rejectsUnauthorizedContextBeforeInspectingOrStoringVideo() throws Exception {
        ErrorCode denied = new ErrorCode() {
            public ErrorType type() {
                return ErrorType.FORBIDDEN;
            }

            public String code() {
                return "TEST_FORBIDDEN";
            }

            public String message() {
                return "검수자 권한이 필요합니다.";
            }
        };
        when(context.requireAuthorizedContext()).thenThrow(new BusinessException(denied));
        mvc.perform(multipart("/api/v1/clips")
                        .header("Idempotency-Key", "test-key")
                        .param("rights_confirmed", "true")
                        .file(upload())
                        .param("source_type", "archive"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(probe, database);
        assertThat(media).isEmptyDirectory();
        assertThat(uploads).isEmptyDirectory();
    }

    private MockMultipartFile upload() {
        return new MockMultipartFile("video", "../../sample.mp4", "video/mp4", new byte[] {1, 2});
    }
}
