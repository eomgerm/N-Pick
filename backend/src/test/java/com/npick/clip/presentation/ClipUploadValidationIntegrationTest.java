package com.npick.clip.presentation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import com.npick.clip.application.command.ClipUploadService;
import com.npick.clip.application.command.StoredClipRegistrationService;
import com.npick.clip.application.command.prepare.PrepareVideoResult;
import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.UploadClipUseCase;
import com.npick.clip.application.port.ClipRegistrationContextPort;
import com.npick.clip.infrastructure.media.FfmpegVideoValidator;
import com.npick.clip.infrastructure.media.FfprobeVideoReader;
import com.npick.clip.infrastructure.media.LocalVideoInspectionAdapter;
import com.npick.clip.infrastructure.media.LocalVideoStorageAdapter;
import com.npick.clip.infrastructure.media.UploadedVideoValidator;
import com.npick.clip.presentation.controller.ClipRegistrationController;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClipRegistrationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ErrorTypeHttpStatusMapper.class})
@EnabledIfEnvironmentVariable(named = "NPICK_MEDIA_TESTS", matches = "true")
class ClipUploadValidationIntegrationTest {
    @TempDir
    Path directory;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UploadClipUseCase useCase;

    private PrepareVideoResult.Metadata metadata;
    private String contentHash;
    private Path media;

    private Path uploads;

    @BeforeEach
    void setUp() throws Exception {
        uploads = Files.createDirectory(directory.resolve("uploads"));
        media = Files.createDirectory(directory.resolve("media"));
        var inspection = new LocalVideoInspectionAdapter(new UploadedVideoValidator(
                uploads,
                new FfprobeVideoReader("ffprobe", Duration.ofSeconds(10), new ObjectMapper()),
                new FfmpegVideoValidator("ffmpeg", Duration.ofSeconds(10))));
        var flow = new ClipUploadService(
                () -> new ClipRegistrationContextPort.Context(7, 101, 201, "test-v1", List.of("scene_detection")),
                command -> {
                    var video = inspection.inspect(command.content());
                    metadata = video.metadata();
                    contentHash = video.contentHash();
                    return video;
                },
                new StoredClipRegistrationService(
                        new LocalVideoStorageAdapter(media),
                        command -> new RegisterClipResult(command.clipId(), command.pipelineRunId(), "queued")),
                (key, actor, hash, request, create) -> create.get(),
                (subtitle, duration, id) -> {
                    throw new AssertionError("No subtitle in this request");
                },
                (rights, external) ->
                        com.npick.clip.domain.policy.RegistrationPermissionPolicy.verify(rights, external, false));
        when(useCase.upload(any())).thenAnswer(call -> flow.upload(call.getArgument(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sample.mp4", "../../outside.mp4", "C:\\outside\\sample.mp4"})
    void validatesMultipartVideoWithoutUsingClientFilenameAsPath(String originalFilename) throws Exception {
        Path video = generateVideo();
        byte[] original = Files.readAllBytes(video);

        mockMvc.perform(request()
                        .file(new MockMultipartFile("video", originalFilename, "video/mp4", original))
                        .param("source_type", "broadcast")
                        .param("filmed_date", "2026-09-07"))
                .andExpect(status().isCreated());

        assertThat(metadata.videoStreams()).containsExactly(new PrepareVideoResult.VideoStream("mpeg4", 160, 120));
        assertThat(metadata.container()).contains("mp4");
        assertThat(metadata.audioCodecs()).isEmpty();
        assertThat(metadata.durationSeconds()).isEqualByComparingTo("2");
        assertThat(contentHash)
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
        assertThat(uploads).isEmptyDirectory();
        assertThat(Files.readAllBytes(video)).isEqualTo(original);
        assertThat(Files.readAllBytes(media.resolve("clips/101/original"))).isEqualTo(original);
    }

    @Test
    void deletesUploadAfterActualProbeFailure() throws Exception {
        var upload = new MockMultipartFile("video", "fake.mp4", "video/mp4", "not a video".getBytes());
        mockMvc.perform(request().file(upload).param("source_type", "broadcast"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLIP_400_001"));
        assertThat(uploads).isEmptyDirectory();
    }

    @Test
    void deletesUploadAfterActualFullDecodeFailure() throws Exception {
        Path video = generateVideo();
        Path packetOutput = directory.resolve("packets.json");
        run(
                packetOutput,
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_packets",
                "-show_entries",
                "packet=pos,size",
                "-of",
                "json",
                video.toString());
        var packets =
                new ObjectMapper().readTree(Files.readString(packetOutput)).path("packets");
        var last = packets.get(packets.size() - 1);
        int position = last.path("pos").asInt();
        byte[] bytes = Files.readAllBytes(video);
        Arrays.fill(bytes, position, position + last.path("size").asInt(), (byte) 0);
        var upload = new MockMultipartFile("video", "damaged.mp4", "video/mp4", bytes);
        mockMvc.perform(request().file(upload).param("source_type", "broadcast"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLIP_400_001"));
        assertThat(uploads).isEmptyDirectory();
    }

    private Path generateVideo() throws Exception {
        Path video = directory.resolve("original.mp4");
        run(
                directory.resolve("generate.log"),
                "ffmpeg",
                "-v",
                "error",
                "-nostdin",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=s=160x120:r=25",
                "-t",
                "2",
                "-c:v",
                "mpeg4",
                "-g",
                "1",
                video.toString());
        return video;
    }

    private void run(Path output, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectOutput(output.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
        }
    }

    private MockMultipartHttpServletRequestBuilder request() {
        return multipart("/api/v1/clips")
                .header("Idempotency-Key", "media-test")
                .param("rights_confirmed", "true");
    }
}
