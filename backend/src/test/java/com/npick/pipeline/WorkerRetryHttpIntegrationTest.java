package com.npick.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import com.npick.pipeline.domain.model.PipelineStages;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerRetryHttpIntegrationTest {
    static final Path ROOT =
            Path.of("build/worker-retry-http-" + UUID.randomUUID()).toAbsolutePath();

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) throws Exception {
        String url = NpickPostgres.freshDatabase("worker_retry_http");
        NpickPostgres.migrate(url);
        NpickPostgres.datasource(properties, url);
        Files.createDirectories(ROOT);
        Path profile = ROOT.resolve("pipeline.yml");
        Files.writeString(profile, "defaults: { retry_count: 1 }\ntransient_errors: [STAGE_TIMEOUT]\n");
        properties.add("npick.pipeline.profile", () -> profile.toUri().toString());
        properties.add("npick.worker-jobs.enabled", () -> true);
        properties.add("npick.worker-jobs.tokens", () -> "t".repeat(32));
        properties.add("npick.clip-registration.media-root", ROOT::toString);
        properties.add("npick.clip-media.media-root", ROOT::toString);
    }

    @Test
    void actualWorkerFailuresRetryAndPersistThroughHttpExecutorAndDatabase() throws Exception {
        Files.createDirectories(ROOT.resolve("clips/912"));
        Files.writeString(ROOT.resolve("clips/912/source.mp4"), "AI test fixture; no real media processing");
        jdbc.update("INSERT INTO npick.member VALUES (911, 'retry-http', 'unused', 'test', 'reviewer', now(), now())");
        jdbc.update("""
                INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash,
                    transcript_source, registered_by_id, created_at, updated_at)
                VALUES (912, 'archive', 'clips/912/source.mp4', ?, 'none', 911, now(), now())
                """, "b".repeat(64));
        for (long run = 913; run <= 916; run++) {
            Map<String, Object> states = new LinkedHashMap<>();
            for (String stage : PipelineStages.NAMES) {
                states.put(
                        stage,
                        Map.of(
                                "status",
                                run == 916 && PipelineStages.NAMES.indexOf(stage) < 5 ? "succeeded" : "pending",
                                "attempts",
                                0,
                                "expectedStageVersion",
                                "npick.stage." + stage + "/v1:aaaaaaaa"));
            }
            jdbc.update(
                    """
                    INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version,
                        status, stage_states_json, created_at, updated_at)
                    VALUES (?, 912, ?, 'pipeline-test', 'queued', ?::jsonb, now(), now())
                    """,
                    run,
                    (int) (run - 912),
                    mapper.writeValueAsString(Map.of("schemaVersion", "npick.stage_states/v1", "stages", states)));
        }
        String python = System.getenv()
                .getOrDefault(
                        "NPICK_TEST_PYTHON",
                        System.getProperty("os.name").startsWith("Windows")
                                ? "../ai/.venv/Scripts/python.exe"
                                : "../ai/.venv/bin/python");
        Path log = ROOT.resolve("probe.log");
        var probe = new ProcessBuilder(
                        python,
                        "-X",
                        "utf8",
                        "../ai/tests/worker_retry_http_probe.py",
                        "http://127.0.0.1:" + port,
                        ROOT.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertThat(probe.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.exitValue()).withFailMessage(Files.readString(log)).isZero();
        } finally {
            if (probe.isAlive()) probe.destroyForcibly();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.scene WHERE pipeline_run_id=913", Long.class))
                .isEqualTo(1);
        for (long run = 913; run <= 916; run++) {
            var row = jdbc.queryForMap(
                    "SELECT status, stage_states_json::text AS states FROM npick.pipeline_run WHERE pipeline_run_id=?",
                    run);
            var state = mapper.readTree((String) row.get("states"))
                    .path("stages")
                    .path(run == 916 ? "asr" : "scene_detection");
            assertThat(state.path("attempts").asInt()).isEqualTo(run == 915 ? 1 : 2);
            assertThat(state.path("status").asText()).isEqualTo(run == 913 ? "succeeded" : "failed");
            assertThat(row.get("status")).isEqualTo(run == 914 || run == 915 ? "failed" : "running");
        }
        assertThat(jdbc.queryForObject("SELECT active_pipeline_run_id FROM npick.clip WHERE clip_id=912", Long.class))
                .isNull();
    }
}
