package com.npick.clip.infrastructure.persistence;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.npick.clip.application.command.ClipRegistrationService;
import com.npick.clip.application.command.ClipUploadService;
import com.npick.clip.application.command.StoredClipRegistrationService;
import com.npick.clip.application.command.VideoPreparationService;
import com.npick.clip.application.command.register.RegisterClipCommand;
import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.RegisterClipUseCase;
import com.npick.clip.application.command.register.StoreAndRegisterClipCommand;
import com.npick.clip.application.error.ClipRuntimeErrorCode;
import com.npick.clip.application.error.RegistrationDeduplicationErrorCode;
import com.npick.clip.application.error.VideoStorageErrorCode;
import com.npick.clip.application.port.ClipRegistrationContextPort;
import com.npick.clip.application.port.RegistrationDeduplicationPort.RequestData;
import com.npick.clip.infrastructure.media.FfmpegVideoValidator;
import com.npick.clip.infrastructure.media.FfprobeVideoReader;
import com.npick.clip.infrastructure.media.LocalVideoInspectionAdapter;
import com.npick.clip.infrastructure.media.LocalVideoStorageAdapter;
import com.npick.clip.infrastructure.media.UploadedVideoValidator;
import com.npick.clip.infrastructure.persistence.repository.JpaClipRegistrationRepository;
import com.npick.clip.infrastructure.persistence.repository.PostgresRegistrationDeduplicationAdapter;
import com.npick.clip.infrastructure.persistence.repository.RegistrationPersistenceAdapter;
import com.npick.clip.infrastructure.security.SessionRegistrationActorAdapter;
import com.npick.clip.presentation.controller.ClipRegistrationController;
import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorCode;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;
import com.npick.common.error.handler.GlobalExceptionHandler;
import com.npick.common.security.AuthenticatedMember;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL/Flyway/JPA and filesystem; only media decoding is replaced in this class. */
// migration 적용은 이 테스트가 직접 단언하며 하므로 컨테스트의 Flyway 는 끕다.
@DataJpaTest(properties = {"spring.flyway.enabled=false", "logging.level.org.hibernate.orm.jdbc.error=OFF"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ClipRegistrationService.class,
    JpaClipRegistrationRepository.class,
    PostgresRegistrationDeduplicationAdapter.class,
    ClipRegistrationPersistenceTest.JsonConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RegistrationDeduplicationIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(10000);
    private static final RequestData REQUEST =
            new RequestData("broadcast", "original", null, null, "private script", null, true, false);

    @Autowired
    RegisterClipUseCase registration;

    @Autowired
    PostgresRegistrationDeduplicationAdapter deduplication;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @TempDir
    Path directory;

    private Path media;
    private Path uploads;
    private LocalVideoInspectionAdapter inspection;

    /**
     * 이 클래스는 baseline 만 적용한 상태에서 후속 migration 이 맞게 얽힐지를 보므로 빈 DB 가 필요하다. 공유 DB 는 이밌 전체 migration 이 적용된 상태다. 같은 컨테이너 안에
     * 전용 DB 를 받는다. 롤백 없이 커밋하는 테스트라 공유 DB 를 오염시키지 않기 위해서도 그렇게 한다.
     */
    /** 회상 프로버젬닝이 만든 전용 DB. 큰션 푸하리 별로 푸을 만드는 테스트가 이 값을 쓴다. */
    private static String url;

    @DynamicPropertySource
    static void provisionDatabase(DynamicPropertyRegistry properties) throws Exception {
        url = NpickPostgres.freshDatabase("npick_dedup");
        String user = NpickPostgres.username();
        String password = NpickPostgres.password();
        // Verify the additive upgrade from the unchanged published baseline, not only a fresh combined install.
        assertThat(Flyway.configure()
                        .dataSource(url, user, password)
                        .defaultSchema("npick")
                        .schemas("npick")
                        .createSchemas(false)
                        .cleanDisabled(true)
                        .locations("classpath:db/migration")
                        .target("20260907092019")
                        .load()
                        .migrate()
                        .migrationsExecuted)
                .isEqualTo(1);
        var flyway = Flyway.configure()
                .dataSource(url, user, password)
                .defaultSchema("npick")
                .schemas("npick")
                .createSchemas(false)
                .cleanDisabled(true)
                .locations("classpath:db/migration")
                .load();
        // baseline 이후 마이그레이션 수. 늘 때마다 같이 올린다
        // (registration_request, pipeline_run_lease, parse_rule_comments, tag_match_value_invisible_chars).
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, user, password);
                var s = c.createStatement()) {
            s.execute("INSERT INTO npick.member VALUES (1,'dedup-test','test-only','검수자','reviewer',now(),now())");
        }
        NpickPostgres.datasource(properties, url);
    }

    @BeforeEach
    void setup() throws Exception {
        media = Files.createDirectory(directory.resolve("media"));
        uploads = Files.createDirectory(directory.resolve("uploads"));
        var probe = mock(FfprobeVideoReader.class);
        when(probe.readVideo(any()))
                .thenReturn(new FfprobeVideoReader.Metadata(
                        "mp4", null, List.of(new FfprobeVideoReader.VideoStream("h264", 320, 240)), List.of()));
        inspection = new LocalVideoInspectionAdapter(
                new UploadedVideoValidator(uploads, probe, mock(FfmpegVideoValidator.class)));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replaySurvivesAdapterRestartAndKeepsOriginalData() {
        long id = IDS.incrementAndGet();
        var first = register(id, "key-" + id, REQUEST);
        var restarted = new PostgresRegistrationDeduplicationAdapter(dataSource);
        assertThat(restarted.register("key-" + id, 1, hash(id), REQUEST, () -> {
                    throw new AssertionError("callback");
                }))
                .isEqualTo(first);
        jdbc.update("UPDATE npick.pipeline_run SET status='completed' WHERE pipeline_run_id=?", id);
        assertThat(restarted.register("key-" + id, 1, hash(id), REQUEST, () -> {
                    throw new AssertionError("callback");
                }))
                .isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.clip WHERE content_hash=?", Long.class, hash(id)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.pipeline_run WHERE clip_id=?", Long.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT title,script_text,registered_by_id FROM npick.clip WHERE clip_id=?", id))
                .containsEntry("title", "original")
                .containsEntry("script_text", "private script")
                .containsEntry("registered_by_id", 1L);
        assertThat(jdbc.queryForObject(
                        "SELECT request_hash FROM npick.registration_request WHERE content_hash=?",
                        String.class,
                        hash(id)))
                .matches("[0-9a-f]{64}")
                .doesNotContain("private");
    }

    @Test
    void eachRequestFieldAndContentParticipateInComparison() {
        long id = IDS.incrementAndGet();
        register(id, "fields-" + id, REQUEST);
        var variants = List.of(
                new RequestData("archive", "original", null, null, "private script", null, true, false),
                new RequestData("broadcast", "changed", null, null, "private script", null, true, false),
                new RequestData(
                        "broadcast", "original", LocalDate.of(2026, 9, 8), null, "private script", null, true, false),
                new RequestData(
                        "broadcast", "original", null, LocalDate.of(2026, 9, 8), "private script", null, true, false),
                new RequestData("broadcast", "original", null, null, "changed", null, true, false),
                new RequestData("broadcast", "original", null, null, "private script", hash(3), true, false),
                new RequestData("broadcast", "original", null, null, "private script", null, false, false),
                new RequestData("broadcast", "original", null, null, "private script", null, true, true));
        for (var variant : variants) {
            error(
                    () -> deduplication.register("fields-" + id, 1, hash(id), variant, () -> {
                        throw new AssertionError("callback");
                    }),
                    RegistrationDeduplicationErrorCode.KEY_CONFLICT);
        }
        error(
                () -> deduplication.register("fields-" + id, 1, hash(id + 1), REQUEST, () -> {
                    throw new AssertionError("callback");
                }),
                RegistrationDeduplicationErrorCode.KEY_CONFLICT);
    }

    @Test
    void differentKeyAndActorSameContentReturnExistingWithoutOverwrite() {
        long id = IDS.incrementAndGet();
        var first = register(id, "first-" + id, REQUEST);
        var changed = new RequestData("archive", "changed", null, null, null, hash(4), true, true);
        assertThat(deduplication.register("second-" + id, 2, hash(id), changed, () -> {
                    throw new AssertionError("callback");
                }))
                .isEqualTo(first);
        assertThat(jdbc.queryForMap("SELECT title,script_text,registered_by_id FROM npick.clip WHERE clip_id=?", id))
                .containsEntry("title", "original")
                .containsEntry("script_text", "private script")
                .containsEntry("registered_by_id", 1L);
    }

    @Test
    void concurrentInstancesRejectProcessingThenReturnOneCommittedResult() throws Exception {
        long id = IDS.incrementAndGet();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondInstance = new PostgresRegistrationDeduplicationAdapter(dataSource);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> deduplication.register("race-" + id, 1, hash(id), REQUEST, () -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return persistence().register(command(id, 1, hash(id)));
            }));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                error(
                        () -> secondInstance.register("race-" + id, 1, hash(id), REQUEST, () -> {
                            throw new AssertionError("callback");
                        }),
                        RegistrationDeduplicationErrorCode.IN_PROGRESS);
                error(
                        () -> secondInstance.register("race-other-" + id, 1, hash(id), REQUEST, () -> {
                            throw new AssertionError("callback");
                        }),
                        RegistrationDeduplicationErrorCode.IN_PROGRESS);
            } finally {
                release.countDown();
            }
            var result = first.get(15, TimeUnit.SECONDS);
            assertThat(secondInstance.register("race-other-" + id, 1, hash(id), REQUEST, () -> {
                        throw new AssertionError("callback");
                    }))
                    .isEqualTo(result);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.clip WHERE content_hash=?", Long.class, hash(id)))
                .isEqualTo(1);
    }

    @Test
    void databaseUniqueConstraintAlsoRejectsWritersBypassingPort() throws Exception {
        long id = IDS.addAndGet(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = java.util.stream.LongStream.of(id, id - 1)
                    .mapToObj(candidate -> executor.submit(() -> {
                        start.await();
                        try {
                            persistence().register(command(candidate, 1, hash(id)));
                            return true;
                        } catch (BusinessException e) {
                            assertThat(e.errorCode()).isEqualTo(ClipRuntimeErrorCode.REGISTRATION_FAILED);
                            return false;
                        }
                    }))
                    .toList();
            start.countDown();
            int count = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS)) count++;
            assertThat(count).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM npick.pipeline_run WHERE clip_id IN (?,?)", Long.class, id, id - 1))
                .isEqualTo(1);
    }

    @Test
    void knownDatabaseRollbackRetainsFingerprintAndAllowsSameRequestRetry() {
        long id = IDS.incrementAndGet();
        error(
                () -> deduplication.register(
                        "retry-" + id, 1, hash(id), REQUEST, () -> persistence().register(command(id, 999, hash(id)))),
                ClipRuntimeErrorCode.REGISTRATION_FAILED);
        assertState(hash(id), "failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.clip WHERE clip_id=?", Long.class, id))
                .isZero();
        assertThat(register(id, "retry-" + id, REQUEST).clipId()).isEqualTo(id);
        assertState(hash(id), "succeeded");
    }

    @Test
    void filesAndRowsSurviveDuplicateReturns() throws Exception {
        long id = IDS.incrementAndGet();
        byte[] bytes = ("video-" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String content;
        RegisterClipResult first;
        try (var video = inspection.inspect(new ByteArrayInputStream(bytes))) {
            content = video.contentHash();
            first = deduplication.register("files-" + id, 1, content, REQUEST, () -> stored(video, id, persistence()));
        }
        try (var video = inspection.inspect(new ByteArrayInputStream(bytes))) {
            assertThat(deduplication.register(
                            "files-other-" + id, 1, content, REQUEST, () -> stored(video, id + 1, persistence())))
                    .isEqualTo(first);
        }
        assertThat(media.resolve("clips/" + id + "/original")).hasBinaryContent(bytes);
        try (var paths = Files.list(media.resolve("clips"))) {
            assertThat(paths.count()).isEqualTo(1);
        }
        assertThat(uploads).isEmptyDirectory();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.clip WHERE content_hash=?", Long.class, content))
                .isEqualTo(1);
    }

    @Test
    void fileCollisionPreservesExistingFileAndAllowsRetryWithNewServerId() throws Exception {
        long id = IDS.addAndGet(2);
        Path original = Files.createDirectories(media.resolve("clips/" + id)).resolve("original");
        Files.writeString(original, "existing");
        try (var video = inspection.inspect(new ByteArrayInputStream(new byte[] {31, 32}))) {
            error(
                    () -> deduplication.register(
                            "collision-" + id, 1, video.contentHash(), REQUEST, () -> stored(video, id, persistence())),
                    VideoStorageErrorCode.DESTINATION_EXISTS);
            assertThat(original).hasContent("existing");
            assertState(video.contentHash(), "failed");
            assertThat(deduplication
                            .register(
                                    "collision-" + id,
                                    1,
                                    video.contentHash(),
                                    REQUEST,
                                    () -> stored(video, id - 1, persistence()))
                            .clipId())
                    .isEqualTo(id - 1);
        }
        assertThat(original).hasContent("existing");
    }

    @Test
    void databaseFailureDiscardsOnlyNewOwnedFile() throws Exception {
        long id = IDS.incrementAndGet();
        try (var video = inspection.inspect(new ByteArrayInputStream(new byte[] {41, 42}))) {
            error(
                    () -> deduplication.register(
                            "db-fail-" + id,
                            1,
                            video.contentHash(),
                            REQUEST,
                            () -> stored(
                                    video,
                                    id,
                                    new RegistrationPersistenceAdapter(
                                            c -> registration.register(command(id, 999, video.contentHash()))))),
                    ClipRuntimeErrorCode.REGISTRATION_FAILED);
            assertThat(media.resolve("clips/" + id)).doesNotExist();
            assertState(video.contentHash(), "failed");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void lostCommitAcknowledgementPreservesFileAndReconcilesOnRetry(boolean errorAfterCommit) throws Exception {
        long id = IDS.incrementAndGet();
        try (var video =
                inspection.inspect(new ByteArrayInputStream(new byte[] {51, 52, (byte) (errorAfterCommit ? 1 : 0)}))) {
            var lostAck = new RegistrationPersistenceAdapter(c -> {
                registration.register(c); // real JPA transaction has committed before the injected acknowledgement loss
                if (errorAfterCommit) throw new AssertionError("injected failure after commit");
                throw new TransactionSystemException("private connection path");
            });
            error(
                    () -> deduplication.register(
                            "lost-" + id, 1, video.contentHash(), REQUEST, () -> stored(video, id, lostAck)),
                    ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
            assertThat(media.resolve("clips/" + id + "/original")).exists();
            assertState(video.contentHash(), "unknown");
            assertThat(deduplication
                            .register("lost-" + id, 1, video.contentHash(), REQUEST, () -> {
                                throw new AssertionError("callback");
                            })
                            .clipId())
                    .isEqualTo(id);
            assertState(video.contentHash(), "succeeded");
        }
    }

    @Test
    void unknownWithoutVisibleCommitBlocksBothSameAndDifferentKeys() throws Exception {
        long id = IDS.incrementAndGet();
        try (var video = inspection.inspect(new ByteArrayInputStream(new byte[] {61, 62}))) {
            var unknown = new RegistrationPersistenceAdapter(c -> {
                throw new TransactionSystemException("lost connection");
            });
            error(
                    () -> deduplication.register(
                            "unknown-" + id, 1, video.contentHash(), REQUEST, () -> stored(video, id, unknown)),
                    ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
            for (String key : List.of("unknown-" + id, "unknown-other-" + id)) {
                error(
                        () -> deduplication.register(key, 1, video.contentHash(), REQUEST, () -> {
                            throw new AssertionError("callback");
                        }),
                        ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
            }
            assertThat(media.resolve("clips/" + id + "/original")).exists();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.clip WHERE clip_id=?", Long.class, id))
                    .isZero();
        }
    }

    @Test
    void journalWriteFailureAfterClipCommitNeverReportsSuccessOrCreatesAgain() {
        long id = IDS.incrementAndGet();
        var failing = new PostgresRegistrationDeduplicationAdapter(failingCompletionDataSource());
        error(
                () -> failing.register(
                        "journal-" + id,
                        1,
                        hash(id),
                        REQUEST,
                        () -> persistence().register(command(id, 1, hash(id)))),
                ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
        assertState(hash(id), "processing");
        assertThat(register(id, "journal-" + id, REQUEST).clipId()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.clip WHERE content_hash=?", Long.class, hash(id)))
                .isEqualTo(1);
    }

    @Test
    void deletedResultRequiresNewKeyAndPartialUniqueIndexAllowsNewClip() {
        long id = IDS.addAndGet(2);
        register(id, "deleted-" + id, REQUEST);
        jdbc.update("UPDATE npick.clip SET deleted_at=now() WHERE clip_id=?", id);
        error(() -> register(id, "deleted-" + id, REQUEST), RegistrationDeduplicationErrorCode.RESULT_DELETED);
        var result = deduplication.register(
                "new-after-delete-" + id,
                1,
                hash(id),
                REQUEST,
                () -> persistence().register(command(id - 1, 1, hash(id))));
        assertThat(result.clipId()).isEqualTo(id - 1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"unknown", "processing"})
    void duplicateThroughAnotherKeyRecoversOriginalAttemptBeforeDeletion(String state) throws Exception {
        long id = IDS.addAndGet(2);
        byte[] bytes = ("lost-response-" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String content;
        try (var video = inspection.inspect(new ByteArrayInputStream(bytes))) {
            content = video.contentHash();
            var adapter = state.equals("processing")
                    ? new PostgresRegistrationDeduplicationAdapter(failingCompletionDataSource())
                    : deduplication;
            var transaction = state.equals("unknown")
                    ? new RegistrationPersistenceAdapter(c -> {
                        registration.register(c);
                        throw new TransactionSystemException("injected lost acknowledgement");
                    })
                    : persistence();
            error(
                    () -> adapter.register("A-" + id, 1, content, REQUEST, () -> stored(video, id, transaction)),
                    ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
        }
        assertState(content, state);
        var existing = deduplication.register("B-" + id, 1, content, REQUEST, () -> {
            throw new AssertionError("duplicate callback");
        });
        assertThat(existing.clipId()).isEqualTo(id);
        // The reported four-step sequence must allow C after B returned successfully.
        jdbc.update("UPDATE npick.clip SET deleted_at=now() WHERE clip_id=?", id);
        try (var video = inspection.inspect(new ByteArrayInputStream(bytes))) {
            assertThat(deduplication
                            .register("C-" + id, 1, content, REQUEST, () -> stored(video, id - 1, persistence()))
                            .clipId())
                    .isEqualTo(id - 1);
        }
        error(
                () -> deduplication.register("A-" + id, 1, content, REQUEST, () -> {
                    throw new AssertionError("old key callback");
                }),
                RegistrationDeduplicationErrorCode.RESULT_DELETED);
        assertThat(media.resolve("clips/" + id + "/original")).hasBinaryContent(bytes);
        assertThat(media.resolve("clips/" + (id - 1) + "/original")).hasBinaryContent(bytes);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM npick.registration_request WHERE content_hash=? AND state IN ('unknown','processing')",
                        Long.class,
                        content))
                .isZero();
    }

    @Test
    void apiUsesAuthenticatedActorAndIgnoresClientPathsWhileReturningDuplicates() throws Exception {
        long id = IDS.incrementAndGet();
        var member = new AuthenticatedMember(1, "dedup-test", "unused", "REVIEWER");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(member, null, member.getAuthorities()));
        var actor = new SessionRegistrationActorAdapter();
        var flow = new ClipUploadService(
                () -> new ClipRegistrationContextPort.Context(
                        actor.requireReviewerId(), id, id, "test-v1", List.of("scene_detection"), false),
                new VideoPreparationService(inspection),
                new StoredClipRegistrationService(new LocalVideoStorageAdapter(media), persistence()),
                deduplication,
                null);
        var mvc = MockMvcBuilders.standaloneSetup(new ClipRegistrationController(flow))
                .setControllerAdvice(new GlobalExceptionHandler(new ErrorTypeHttpStatusMapper()))
                .build();
        for (String filename : List.of("../escape.mp4", "/absolute/escape.mp4", "C:\\private\\escape.mp4")) {
            mvc.perform(multipart("/api/v1/clips")
                            .file(new MockMultipartFile("video", filename, "video/mp4", new byte[] {71, 72}))
                            .header("Idempotency-Key", "api-" + id)
                            .param("source_type", "archive")
                            .param("rights_confirmed", "true")
                            .param("registered_by_id", "999")
                            .param("storage_key", "../../escape")
                            .param("title", "API original"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.clip_id").value(Long.toString(id)));
        }
        mvc.perform(multipart("/api/v1/clips")
                        .file(new MockMultipartFile("video", "x.mp4", "video/mp4", new byte[] {71, 72}))
                        .header("Idempotency-Key", "api-" + id)
                        .param("source_type", "archive")
                        .param("rights_confirmed", "true")
                        .param("title", "changed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIP_409_001"));
        assertThat(jdbc.queryForMap("SELECT registered_by_id,storage_key,title FROM npick.clip WHERE clip_id=?", id))
                .containsEntry("registered_by_id", 1L)
                .containsEntry("storage_key", "clips/" + id + "/original")
                .containsEntry("title", "API original");
        assertThat(uploads).isEmptyDirectory();
        try (var paths = Files.walk(media)) {
            assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(1);
        }
    }

    @Test
    void resolvingOneContentDoesNotReleaseUnrelatedUnknownRequests() {
        long id = IDS.addAndGet(2);
        error(
                () -> deduplication.register("unresolved-negative-" + id, 1, hash(id), REQUEST, () -> {
                    throw new BusinessException(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
                }),
                ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
        register(id - 1, "other-" + id, REQUEST);
        deduplication.register("other-duplicate-" + id, 1, hash(id - 1), REQUEST, () -> {
            throw new AssertionError("callback");
        });
        assertState(hash(id), "unknown");
        error(
                () -> register(id, "another-unresolved-" + id, REQUEST),
                ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
    }

    @Test
    void successfulReplayAlsoRepairsOlderUnresolvedAliases() {
        long id = IDS.incrementAndGet();
        register(id, "success-before-recovery-" + id, REQUEST);
        // Simulate a result-journal acknowledgement loss for an alias, without changing clip/run data.
        error(
                () -> new PostgresRegistrationDeduplicationAdapter(failingCompletionDataSource())
                        .register("alias-before-recovery-" + id, 1, hash(id), REQUEST, () -> {
                            throw new AssertionError("callback");
                        }),
                ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
        register(id, "success-before-recovery-" + id, REQUEST);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM npick.registration_request WHERE content_hash=? AND state<>'succeeded'",
                        Long.class,
                        hash(id)))
                .isZero();
    }

    @Test
    void releasesOnlyOwnedSessionLocks() throws Exception {
        long id = IDS.incrementAndGet();
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(68680001)");
            try {
                var pinned = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
                var adapter = new PostgresRegistrationDeduplicationAdapter(pinned);
                adapter.register(
                        "owned-locks-" + id,
                        1,
                        hash(id),
                        REQUEST,
                        () -> persistence().register(command(id, 1, hash(id))));
                try (var rows = statement.executeQuery(
                        "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid()")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
            } finally {
                statement.execute("SELECT pg_advisory_unlock(68680001)");
            }
        }
    }

    @Test
    void unknownDeletedBeforeAnyObservationIsNotBlindlyMadeRetryable() {
        long id = IDS.incrementAndGet();
        error(
                () -> deduplication.register("deleted-unobserved-" + id, 1, hash(id), REQUEST, () -> {
                    persistence().register(command(id, 1, hash(id)));
                    throw new BusinessException(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
                }),
                ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
        jdbc.update("UPDATE npick.clip SET deleted_at=now() WHERE clip_id=?", id);
        error(
                () -> deduplication.register("deleted-unobserved-other-" + id, 1, hash(id), REQUEST, () -> {
                    throw new AssertionError("unproven callback");
                }),
                ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
        assertState(hash(id), "unknown");
    }

    @Test
    void exhaustedConnectionPoolDoesNotAcceptOrExecuteARequest() throws Exception {
        long id = IDS.incrementAndGet();
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(NpickPostgres.username());
        config.setPassword(NpickPostgres.password());
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(300);
        try (var limited = new com.zaxxer.hikari.HikariDataSource(config)) {
            var adapter = new PostgresRegistrationDeduplicationAdapter(limited);
            try (var occupied = limited.getConnection()) {
                assertThat(occupied.isValid(2)).isTrue();
                error(
                        () -> adapter.register("pool-" + id, 1, hash(id), REQUEST, () -> {
                            throw new AssertionError("pool exhausted callback");
                        }),
                        ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
            }
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM npick.registration_request WHERE content_hash=?",
                            Long.class,
                            hash(id)))
                    .isZero();
            // Callback uses the main JPA pool, so the single-slot journal pool is sufficient here.
            assertThat(adapter.register(
                                    "pool-" + id,
                                    1,
                                    hash(id),
                                    REQUEST,
                                    () -> persistence().register(command(id, 1, hash(id))))
                            .clipId())
                    .isEqualTo(id);
        }
    }

    @Test
    void failedUnlockAbortsConnectionAndCommittedResultRemainsReplayable() throws Exception {
        long id = IDS.incrementAndGet();
        var failing = new PostgresRegistrationDeduplicationAdapter(
                failingStatementDataSource("pg_advisory_unlock(", "execute"));
        error(
                () -> failing.register(
                        "unlock-" + id,
                        1,
                        hash(id),
                        REQUEST,
                        () -> persistence().register(command(id, 1, hash(id)))),
                ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
        assertThat(register(id, "unlock-" + id, REQUEST).clipId()).isEqualTo(id);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND database=(SELECT oid FROM pg_database WHERE datname=current_database())",
                        Long.class))
                .isZero();
    }

    @Test
    void failedReservationNeverCallsCreatorAndLeavesNoLocks() {
        long id = IDS.incrementAndGet();
        var failing = new PostgresRegistrationDeduplicationAdapter(
                failingStatementDataSource("INSERT INTO npick.registration_request", "executeUpdate"));
        error(
                () -> failing.register("reserve-fail-" + id, 1, hash(id), REQUEST, () -> {
                    throw new AssertionError("callback");
                }),
                ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM npick.registration_request WHERE content_hash=?", Long.class, hash(id)))
                .isZero();
        assertThat(register(id, "reserve-fail-" + id, REQUEST).clipId()).isEqualTo(id);
    }

    @Test
    void requestJournalRejectsPartialResultsAndInvalidIdentity() {
        String content = hash(IDS.incrementAndGet());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO npick.registration_request(actor_id,key_hash,request_hash,content_hash,state,clip_id)
                VALUES(1,?,?,?,'processing',42)
                """, content, content, content))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO npick.registration_request(actor_id,key_hash,request_hash,content_hash,state)
                VALUES(1,?,?,?,'succeeded')
                """, content, content, content))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO npick.registration_request(actor_id,key_hash,request_hash,content_hash,state)
                VALUES(0,'not-a-hash',?,?,'processing')
                """, content, content))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void actualJournalBackendTerminationBlocksCompetitorUntilCommitCanBeObserved() throws Exception {
        long id = IDS.incrementAndGet();
        byte[] bytes = ("terminated-" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var connection = dataSource.getConnection();
                var video = inspection.inspect(new ByteArrayInputStream(bytes));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            int pid;
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                pid = rows.getInt(1);
            }
            var pinned = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
            var adapter = new PostgresRegistrationDeduplicationAdapter(pinned);
            var first =
                    executor.submit(() -> adapter.register("terminated-" + id, 1, video.contentHash(), REQUEST, () -> {
                        entered.countDown();
                        try {
                            if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                        return stored(video, id, persistence());
                    }));
            try {
                assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                // Real PostgreSQL session termination, not a mocked SQLException.
                assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?, 5000)", Boolean.class, pid))
                        .isTrue();
                error(
                        () -> deduplication.register(
                                "terminated-competitor-" + id, 1, video.contentHash(), REQUEST, () -> {
                                    throw new AssertionError("competitor callback");
                                }),
                        ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> first.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e -> assertThat(e.errorCode())
                                    .isEqualTo(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN));
            var recovered =
                    deduplication.register("terminated-competitor-" + id, 1, video.contentHash(), REQUEST, () -> {
                        throw new AssertionError("recovery callback");
                    });
            assertThat(recovered.clipId()).isEqualTo(id);
            assertThat(media.resolve("clips/" + id + "/original")).hasBinaryContent(bytes);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM npick.registration_request WHERE content_hash=? AND state IN ('processing','unknown')",
                            Long.class,
                            video.contentHash()))
                    .isZero();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NPICK_MEDIA_TESTS", matches = "true")
    void productionConfigurationRegistersAndReplaysRealVideoThroughApiAndDatabase() throws Exception {
        Path input = directory.resolve("fixture.mp4");
        var generator = new ProcessBuilder(
                        "ffmpeg",
                        "-v",
                        "error",
                        "-nostdin",
                        "-f",
                        "lavfi",
                        "-i",
                        "testsrc2=s=160x120:r=25",
                        "-t",
                        "1",
                        "-c:v",
                        "libx264",
                        input.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            assertThat(generator.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(generator.exitValue()).isZero();
        } finally {
            generator.destroyForcibly();
        }
        var member = new AuthenticatedMember(1, "dedup-test", "unused", "REVIEWER");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(member, null, member.getAuthorities()));
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            org.springframework.boot.test.util.TestPropertyValues.of(
                            "npick.clip-registration.media-root=" + media,
                            "npick.clip-registration.upload-root=" + uploads,
                            "npick.clip-registration.probe-timeout=30s",
                            "npick.clip-registration.decode-timeout=30s",
                            "npick.clip-registration.pipeline-version=test-v1",
                            "npick.clip-registration.stage-names[0]=scene_detection",
                            "npick.clip-registration.external-processing-required=false",
                            "npick.clip-registration.input.max-file-bytes=1048576",
                            "npick.clip-registration.input.max-duration-seconds=10",
                            "npick.clip-registration.input.allowed-containers[0]=mp4",
                            "npick.clip-registration.input.allowed-video-codecs[0]=h264",
                            "npick.clip-registration.input.allowed-audio-codecs[0]=aac")
                    .applyTo(context);
            context.registerBean(RegisterClipUseCase.class, () -> registration);
            context.registerBean(
                    com.npick.clip.application.port.RegistrationDeduplicationPort.class, () -> deduplication);
            context.registerBean(
                    com.npick.clip.application.port.RegistrationActorPort.class, SessionRegistrationActorAdapter::new);
            context.registerBean(
                    tools.jackson.databind.json.JsonMapper.class,
                    () -> tools.jackson.databind.json.JsonMapper.builder().build());
            context.register(
                    com.npick.clip.infrastructure.config.ClipRegistrationConfiguration.class,
                    com.npick.pipeline.infrastructure.config.PipelineDefinitionConfiguration.class);
            context.refresh();
            var flow = context.getBean(com.npick.clip.application.command.register.UploadClipUseCase.class);
            var mvc = MockMvcBuilders.standaloneSetup(new ClipRegistrationController(flow))
                    .setControllerAdvice(new GlobalExceptionHandler(new ErrorTypeHttpStatusMapper()))
                    .build();
            byte[] bytes = Files.readAllBytes(input);
            byte[] subtitleBytes = "\uFEFF1\r\n00:00:00,123 --> 00:00:00,987\r\n한글 원본\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String first = null;
            for (String key : List.of("actual-video", "actual-video", "actual-video-other")) {
                var response = mvc.perform(multipart("/api/v1/clips")
                                .file(new MockMultipartFile("video", "../../fixture.mp4", "video/mp4", bytes))
                                .file(new MockMultipartFile(
                                        "subtitle", "../../provided.srt", "text/plain", subtitleBytes))
                                .header("Idempotency-Key", key)
                                .param("source_type", "archive")
                                .param("script_text", "영상 전체 참고 대본")
                                .param("rights_confirmed", "true"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
                if (first == null) first = response;
                else assertThat(response).isEqualTo(first);
            }
            var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(first);
            long clipId = Long.parseLong(json.path("data").path("clip_id").asText());
            assertThat(media.resolve("clips/" + clipId + "/original")).hasBinaryContent(bytes);
            String subtitleKey = jdbc.queryForObject(
                    "SELECT transcript_file_key FROM npick.clip WHERE clip_id=?", String.class, clipId);
            assertThat(media.resolve(subtitleKey)).hasBinaryContent(subtitleBytes);
            assertThat(jdbc.queryForObject("SELECT script_text FROM npick.clip WHERE clip_id=?", String.class, clipId))
                    .isEqualTo("영상 전체 참고 대본");
            assertThat(jdbc.queryForObject(
                            "SELECT registered_by_id FROM npick.clip WHERE clip_id=?", Long.class, clipId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM npick.pipeline_run WHERE clip_id=?", Long.class, clipId))
                    .isEqualTo(1);
            try (var files = Files.walk(media)) {
                assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(2);
            }
            assertThat(uploads).isEmptyDirectory();
            mvc.perform(multipart("/api/v1/clips")
                            .file(new MockMultipartFile("video", "fixture.mp4", "video/mp4", bytes))
                            .file(new MockMultipartFile("subtitle", "provided.srt", "text/plain", new byte[] {1}))
                            .header("Idempotency-Key", "actual-subtitle")
                            .param("source_type", "archive")
                            .param("rights_confirmed", "true"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CLIP_400_012"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("line 1")));
            assertThat(media.resolve(subtitleKey)).hasBinaryContent(subtitleBytes);
            // 같은 요청 키의 원본 바이트 차이는 정규화된 내용이 같아도 충돌이다.
            mvc.perform(multipart("/api/v1/clips")
                            .file(new MockMultipartFile("video", "fixture.mp4", "video/mp4", bytes))
                            .file(new MockMultipartFile(
                                    "subtitle",
                                    "provided.srt",
                                    "text/plain",
                                    new String(subtitleBytes, java.nio.charset.StandardCharsets.UTF_8)
                                            .replace("\r\n", "\n")
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                            .header("Idempotency-Key", "actual-video")
                            .param("source_type", "archive")
                            .param("script_text", "영상 전체 참고 대본")
                            .param("rights_confirmed", "true"))
                    .andExpect(status().isConflict());
            try (var files = Files.walk(media)) {
                assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(2);
            }
        }
    }

    @Test
    void preservesSqlFailureCause() {
        long id = IDS.incrementAndGet();
        var failing = new PostgresRegistrationDeduplicationAdapter(
                failingStatementDataSource("INSERT INTO npick.registration_request", "executeUpdate"));
        assertThatThrownBy(() -> failing.register("cause-sql-" + id, 1, hash(id), REQUEST, () -> {
                    throw new AssertionError("callback must not run");
                }))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
                    assertThat(e.getCause()).isInstanceOf(SQLException.class);
                });
    }

    @Test
    void unlockFailureRetainsTheOriginalOperationFailure() {
        long id = IDS.incrementAndGet();
        var original = new AssertionError("private operation failure");
        var failing = new PostgresRegistrationDeduplicationAdapter(
                failingStatementDataSource("pg_advisory_unlock(", "execute"));
        assertThatThrownBy(() -> failing.register("cause-unlock-" + id, 1, hash(id), REQUEST, () -> {
                    throw original;
                }))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(SQLException.class);
                    // Pool close may append another failure after abort; the operation must still be retained.
                    assertThat(e.getCause().getSuppressed())
                            .anySatisfy(secondary ->
                                    assertThat(secondary.getCause()).isSameAs(original));
                });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void preservesCreatorErrorAndSecondaryJournalFailure(boolean journalFails) {
        long id = IDS.incrementAndGet();
        var original = new AssertionError("private original failure");
        var adapter = journalFails
                ? new PostgresRegistrationDeduplicationAdapter(
                        failingStatementDataSource("SET state=?,", "executeUpdate"))
                : deduplication;
        assertThatThrownBy(() -> adapter.register("cause-error-" + id, 1, hash(id), REQUEST, () -> {
                    throw original;
                }))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
                    assertThat(e.getCause()).isSameAs(original);
                    assertThat(e.getSuppressed()).hasSize(journalFails ? 1 : 0);
                    if (journalFails) assertThat(e.getSuppressed()[0]).isInstanceOf(SQLException.class);
                });
        assertState(hash(id), journalFails ? "processing" : "unknown");
    }

    private RegisterClipResult stored(
            com.npick.clip.application.command.prepare.PrepareVideoResult video,
            long id,
            RegisterClipUseCase database) {
        return new StoredClipRegistrationService(new LocalVideoStorageAdapter(media), database)
                .register(new StoreAndRegisterClipCommand(
                        video,
                        id,
                        id,
                        "broadcast",
                        "original",
                        null,
                        null,
                        null,
                        "private script",
                        1,
                        "test-v1",
                        List.of("scene_detection")));
    }

    private RegisterClipResult register(long id, String key, RequestData request) {
        return deduplication.register(
                key, 1, hash(id), request, () -> persistence().register(command(id, 1, hash(id))));
    }

    private RegisterClipUseCase persistence() {
        return new RegistrationPersistenceAdapter(registration);
    }

    private static RegisterClipCommand command(long id, long actor, String content) {
        return new RegisterClipCommand(
                "broadcast",
                "original",
                null,
                null,
                "clips/" + id + "/original",
                content,
                null,
                "private script",
                actor,
                id,
                id,
                "test-v1",
                List.of("scene_detection"));
    }

    private static String hash(long id) {
        return String.format("%064x", id);
    }

    private void assertState(String content, String state) {
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM npick.registration_request WHERE content_hash=?", String.class, content))
                .isEqualTo(state);
    }

    private static void error(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
    }

    private DataSource failingCompletionDataSource() {
        return failingStatementDataSource("SET state='succeeded'", "executeUpdate");
    }

    private DataSource failingStatementDataSource(String sqlToken, String failingMethod) {
        return new AbstractDataSource() {
            public Connection getConnection() throws SQLException {
                return wrap(dataSource.getConnection());
            }

            public Connection getConnection(String user, String password) throws SQLException {
                return getConnection();
            }

            private Connection wrap(Connection delegate) {
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                            try {
                                Object result = method.invoke(delegate, args);
                                if (method.getName().equals("prepareStatement")
                                        && ((String) args[0]).contains(sqlToken)) {
                                    return Proxy.newProxyInstance(
                                            PreparedStatement.class.getClassLoader(),
                                            new Class<?>[] {PreparedStatement.class},
                                            (p, m, a) -> {
                                                if (m.getName().equals(failingMethod))
                                                    throw new SQLException("injected journal failure");
                                                try {
                                                    return m.invoke(result, a);
                                                } catch (InvocationTargetException e) {
                                                    throw e.getCause();
                                                }
                                            });
                                }
                                return result;
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
    }
}
