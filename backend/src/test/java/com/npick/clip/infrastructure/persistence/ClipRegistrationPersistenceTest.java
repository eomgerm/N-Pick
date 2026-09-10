package com.npick.clip.infrastructure.persistence;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import com.npick.clip.application.command.ClipRegistrationService;
import com.npick.clip.application.command.StoredClipRegistrationService;
import com.npick.clip.application.command.prepare.PrepareVideoResult;
import com.npick.clip.application.command.register.RegisterClipCommand;
import com.npick.clip.application.command.register.RegisterClipUseCase;
import com.npick.clip.application.command.register.StoreAndRegisterClipCommand;
import com.npick.clip.infrastructure.media.FfmpegVideoValidator;
import com.npick.clip.infrastructure.media.FfprobeVideoReader;
import com.npick.clip.infrastructure.media.LocalVideoInspectionAdapter;
import com.npick.clip.infrastructure.media.LocalVideoStorageAdapter;
import com.npick.clip.infrastructure.media.UploadedVideoValidator;
import com.npick.clip.infrastructure.persistence.repository.JpaClipRegistrationRepository;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ClipRegistrationService.class,
    JpaClipRegistrationRepository.class,
    ClipRegistrationPersistenceTest.JsonConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ClipRegistrationPersistenceTest {
    @TempDir
    Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "NPICK_MEDIA_TESTS", matches = "true")
    void registersActualMultipartVideoThroughRuntimeAndDatabase() throws Exception {
        Path input = directory.resolve("input.mp4");
        Process generator = new ProcessBuilder(
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
                        "libx264",
                        input.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            assertThat(generator.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(generator.exitValue()).isZero();
        } finally {
            if (generator.isAlive()) generator.destroyForcibly().waitFor();
        }
        Path media = directory.resolve("media");
        Path uploads = directory.resolve("uploads");
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        var defaults = new org.springframework.boot.env.YamlPropertySourceLoader()
                                .load(
                                        "registration-defaults",
                                        new org.springframework.core.io.FileSystemResource(
                                                "src/main/resources/application.yml"))
                                .getFirst();
                        context.getEnvironment().getPropertySources().addLast(defaults);
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                })
                .withUserConfiguration(com.npick.clip.infrastructure.config.ClipRegistrationConfiguration.class)
                .withPropertyValues(
                        "npick.clip-registration.media-root=" + media, "npick.clip-registration.upload-root=" + uploads)
                .withBean(tools.jackson.databind.ObjectMapper.class, tools.jackson.databind.ObjectMapper::new)
                .withBean(com.npick.clip.application.port.RegistrationActorPort.class, () -> () -> 1)
                .withBean(
                        com.npick.clip.application.port.RegistrationDeduplicationPort.class,
                        () -> (key, actor, hash, request, create) -> create.get())
                .withBean(RegisterClipUseCase.class, () -> useCase)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var controller = new com.npick.clip.presentation.controller.ClipRegistrationController(
                            context.getBean(com.npick.clip.application.command.register.UploadClipUseCase.class));
                    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                            .setControllerAdvice(new com.npick.common.error.handler.GlobalExceptionHandler(
                                    new com.npick.common.error.handler.ErrorTypeHttpStatusMapper()))
                            .build();
                    var response = mvc.perform(
                                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                                                    "/api/v1/clips")
                                            .file(new org.springframework.mock.web.MockMultipartFile(
                                                    "video", "sample.mp4", "video/mp4", Files.readAllBytes(input)))
                                            .header("Idempotency-Key", "actual-e2e")
                                            .param("source_type", "broadcast")
                                            .param("rights_confirmed", "true")
                                            .param("filmed_date", "2026-09-08"))
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                    .isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
                    var data = JsonMapper.builder().build().readTree(response).path("data");
                    long clipId = Long.parseLong(data.path("clip_id").asText());
                    long runId = Long.parseLong(data.path("pipeline_run_id").asText());
                    assertThat(jdbc.queryForObject(
                                    "SELECT clip_id FROM npick.pipeline_run WHERE pipeline_run_id=?",
                                    Long.class,
                                    runId))
                            .isEqualTo(clipId);
                    assertThat(jdbc.queryForObject(
                                    "SELECT status FROM npick.pipeline_run WHERE pipeline_run_id=?",
                                    String.class,
                                    runId))
                            .isEqualTo("queued");
                    assertThat(jdbc.queryForObject(
                                    "SELECT active_pipeline_run_id FROM npick.clip WHERE clip_id=?",
                                    Long.class,
                                    clipId))
                            .isNull();
                    assertThat(jdbc.queryForObject(
                                    "SELECT pipeline_version FROM npick.pipeline_run WHERE pipeline_run_id=?",
                                    String.class,
                                    runId))
                            .isEqualTo("pipeline-v1");
                    assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM jsonb_object_keys((SELECT stage_states_json FROM npick.pipeline_run WHERE pipeline_run_id=?))",
                                    Integer.class,
                                    runId))
                            .isEqualTo(10);
                    String key = jdbc.queryForObject(
                            "SELECT storage_key FROM npick.clip WHERE clip_id=?", String.class, clipId);
                    assertThat(Files.readAllBytes(media.resolve(key))).isEqualTo(Files.readAllBytes(input));
                    assertThat(uploads).isEmptyDirectory();
                });
    }

    @Test
    void keepsFileAfterCommitAndRemovesOnlyFailedRegistrationFile() throws Exception {
        Path uploads = Files.createDirectory(directory.resolve("uploads"));
        Path media = Files.createDirectory(directory.resolve("media"));
        var reader = mock(FfprobeVideoReader.class);
        when(reader.readVideo(any()))
                .thenReturn(new FfprobeVideoReader.Metadata(
                        "mp4", null, List.of(new FfprobeVideoReader.VideoStream("h264", 320, 240)), List.of()));
        var inspection = new LocalVideoInspectionAdapter(
                new UploadedVideoValidator(uploads, reader, mock(FfmpegVideoValidator.class)));
        var flow = new StoredClipRegistrationService(new LocalVideoStorageAdapter(media), useCase);
        try (var video = inspection.inspect(new ByteArrayInputStream(new byte[] {10}))) {
            var result = flow.register(storedCommand(video, 107, 207));
            assertThat(result.clipId()).isEqualTo(107);
            assertThat(jdbc.queryForObject("SELECT content_hash FROM npick.clip WHERE clip_id=107", String.class))
                    .isEqualTo(video.contentHash());
        }
        assertThat(Files.readAllBytes(media.resolve("clips/107/original"))).containsExactly((byte) 10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.tagging WHERE clip_id=107", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM npick.pipeline_run WHERE pipeline_run_id=207", String.class))
                .isEqualTo("queued");
        try (var video = inspection.inspect(new ByteArrayInputStream(new byte[] {11}))) {
            assertThatThrownBy(() -> flow.register(new StoreAndRegisterClipCommand(
                            video,
                            108,
                            207,
                            "broadcast",
                            null,
                            LocalDate.of(2026, 9, 8),
                            null,
                            null,
                            null,
                            1,
                            "test-pipeline-v1",
                            STAGES)))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.tagging WHERE clip_id=108", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT clip_id FROM npick.pipeline_run WHERE pipeline_run_id=207", Long.class))
                .isEqualTo(107);
        assertThat(media.resolve("clips/108")).doesNotExist();
        assertThat(Files.readAllBytes(media.resolve("clips/107/original"))).containsExactly((byte) 10);
        assertThat(uploads).isEmptyDirectory();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.clip WHERE clip_id=108", Long.class))
                .isZero();
    }

    private static StoreAndRegisterClipCommand storedCommand(PrepareVideoResult video, long clipId, long runId) {
        return new StoreAndRegisterClipCommand(
                video, clipId, runId, "archive", null, null, null, null, null, 1, "test-pipeline-v1", STAGES);
    }

    @Autowired
    private RegisterClipUseCase useCase;

    @Autowired
    private JdbcTemplate jdbc;

    private static final List<String> STAGES = List.of("scene_detection", "frame_extraction");

    /**
     * 이 클래스는 {@code NOT_SUPPORTED} 로 돌아 롤백하지 않고 커밋한다. 공유 DB 를 쓰면 남은 행이 다른 클래스의 전역 단언을 깨뜨린다. 전용 DB 를 받아 스키마·검수자까지 여기서
     * 준비한다. 컨테스트의 Flyway 는 뒤이어 무변경 validate 만 한다.
     */
    @DynamicPropertySource
    static void provisionDatabase(DynamicPropertyRegistry properties) {
        String url = NpickPostgres.freshDatabase("npick_clip_registration");
        NpickPostgres.migrate(url);
        NpickPostgres.execute(
                url,
                "INSERT INTO npick.member VALUES (1, 'registration-test', 'test-only', '검수자', 'reviewer', now(), now())");
        NpickPostgres.datasource(properties, url);
    }

    @Test
    void persistsClipRunAndIndependentDateEvidence() {
        var result =
                useCase.register(command(101, 201, "broadcast", LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 6)));
        assertThat(result.clipId()).isEqualTo(101);
        assertThat(result.pipelineRunId()).isEqualTo(201);
        assertThat(result.status()).isEqualTo("queued");
        var clip = jdbc.queryForMap("SELECT * FROM npick.clip WHERE clip_id=101");
        assertThat(clip)
                .containsEntry("storage_key", "clips/101/original")
                .containsEntry("content_hash", hash(101))
                .containsEntry("registered_by_id", 1L)
                .containsEntry("transcript_source", "none")
                .containsEntry("active_pipeline_run_id", null)
                .containsEntry("deleted_at", null);
        var run = jdbc.queryForMap("SELECT * FROM npick.pipeline_run WHERE pipeline_run_id=201");
        assertThat(run)
                .containsEntry("clip_id", 101L)
                .containsEntry("processing_no", 1)
                .containsEntry("pipeline_version", "test-pipeline-v1")
                .containsEntry("status", "queued")
                .containsEntry("started_at", null)
                .containsEntry("finished_at", null)
                .containsEntry("error_code", null);
        var states = JsonMapper.builder()
                .build()
                .readTree(run.get("stage_states_json").toString());
        assertThat(states.size()).isEqualTo(2);
        STAGES.forEach(stage -> {
            assertThat(states.path(stage).path("status").asText()).isEqualTo("pending");
            assertThat(states.path(stage).path("attempts").asInt()).isZero();
        });
        var dates = jdbc.queryForList("""
            SELECT t.tag_type, t.match_value, g.scene_id, e.source, e.verification_status, e.confidence, e.source_ref_id
            FROM npick.tag t JOIN npick.tagging g USING(tag_id) JOIN npick.tag_evidence e USING(tagging_id)
            WHERE g.clip_id=101 ORDER BY t.tag_type
            """);
        assertThat(dates).hasSize(2);
        assertThat(dates.get(0)).containsEntry("tag_type", "broadcast_date").containsEntry("match_value", "2026-09-07");
        assertThat(dates.get(1)).containsEntry("tag_type", "filmed_date").containsEntry("match_value", "2026-09-06");
        dates.forEach(date -> assertThat(date)
                .containsEntry("scene_id", null)
                .containsEntry("source", "user_input")
                .containsEntry("verification_status", "unverified")
                .containsEntry("confidence", null)
                .containsEntry("source_ref_id", null));
    }

    @Test
    void concurrentRegistrationsShareDateTagAndKeepSeparateEvidence() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> first = () -> {
                start.await();
                useCase.register(command(105, 205, "archive", null, LocalDate.of(2026, 1, 1)));
                return null;
            };
            Callable<Void> second = () -> {
                start.await();
                useCase.register(command(106, 206, "archive", null, LocalDate.of(2026, 1, 1)));
                return null;
            };
            var a = executor.submit(first);
            var b = executor.submit(second);
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM npick.tag WHERE tag_type='filmed_date' AND match_value='2026-01-01'",
                        Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM npick.tagging g JOIN npick.tag_evidence e USING(tagging_id)
            WHERE g.clip_id IN (105,106)
            """, Long.class)).isEqualTo(2);
    }

    private static RegisterClipCommand command(
            long clipId, long runId, String sourceType, LocalDate broadcast, LocalDate filmed) {
        return new RegisterClipCommand(
                sourceType,
                "등록 테스트",
                broadcast,
                filmed,
                "clips/" + clipId + "/original",
                hash(clipId),
                null,
                null,
                1,
                clipId,
                runId,
                "test-pipeline-v1",
                STAGES);
    }

    private static String hash(long clipId) {
        return String.format("%064x", clipId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
