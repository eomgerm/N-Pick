package com.npick.pipeline;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** Real HTTP, Python runner, executor, transcript preparation, artifacts and PostgreSQL. Only AI is mocked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerHttpIntegrationTest {
    static final Path ROOT = Path.of("build/worker-http-" + UUID.randomUUID()).toAbsolutePath();

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties, Database.URL);
        properties.add("npick.worker-jobs.enabled", () -> true);
        properties.add("npick.worker-jobs.tokens", () -> "t".repeat(32) + "," + "r".repeat(32));
        properties.add("npick.clip-registration.media-root", ROOT::toString);
        properties.add("npick.clip-media.media-root", ROOT::toString);
        properties.add(
                "npick.clip-registration.upload-root",
                () -> ROOT.resolve("uploads").toString());
    }

    private static class Database {
        static final String URL = create();

        private static String create() {
            String url = NpickPostgres.freshDatabase("worker_http");
            NpickPostgres.migrate(url);
            return url;
        }
    }

    @Test
    void actualExecutorHttpLiveAndOfflineRoundTrips() throws Exception {
        Files.createDirectories(ROOT.resolve("clips/802"));
        // Real, tiny media lets the stored #35 preparation path run ffprobe without a BE test double.
        Path media = ROOT.resolve("clips/802/source.mp4");
        Path ffmpegLog = ROOT.resolve("ffmpeg.log");
        var generate = new ProcessBuilder(
                        "ffmpeg",
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "color=c=black:s=32x32:r=10:d=1",
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        "yuv420p",
                        media.toString())
                .redirectErrorStream(true)
                .redirectOutput(ffmpegLog.toFile())
                .start();
        assertThat(generate.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(generate.exitValue())
                .withFailMessage(Files.readString(ffmpegLog))
                .isZero();
        Files.writeString(ROOT.resolve("clips/802/subtitle.srt"), "1\n00:00:00,000 --> 00:00:01,000\n실제 단계 입력\n");
        Files.createDirectories(ROOT.resolve("clips/999"));
        Files.writeString(ROOT.resolve("clips/999/source.mp4"), "another clip");
        jdbc.update("INSERT INTO npick.member VALUES (801, 'worker-http', 'unused', 'test', 'reviewer', now(), now())");
        jdbc.update("""
            INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_file_key,
                transcript_source, registered_by_id, created_at, updated_at)
            VALUES (802, 'archive', 'clips/802/source.mp4', ?, 'clips/802/subtitle.srt', 'none', 801, now(), now())
            """, "a".repeat(64));
        // Earlier AI stages are fixture state, not claimed as verified executions.
        for (long run : new long[] {803, 804}) {
            Map<String, Object> states = new LinkedHashMap<>();
            for (String stage : PipelineStages.NAMES) {
                states.put(
                        stage,
                        Map.of(
                                "status",
                                PipelineStages.NAMES.indexOf(stage)
                                                < PipelineStages.NAMES.indexOf("transcript_selection")
                                        ? "succeeded"
                                        : "pending",
                                "attempts",
                                0,
                                "expectedStageVersion",
                                "npick.stage." + stage + "/v1:aaaaaaaa"));
            }
            // Accepted top-level fields do not grant the permissions claimed by extra nested fields.
            states.put(
                    "frame_extraction",
                    Map.of(
                            "status",
                            "succeeded",
                            "attempts",
                            1,
                            "expectedStageVersion",
                            "npick.stage.frame_extraction/v1:aaaaaaaa",
                            "artifacts",
                            java.util.List.of(Map.of(
                                    "kind",
                                    "keyframe",
                                    "storageKey",
                                    "runs/" + run + "/frame_extraction/a1/frame.jpg",
                                    "byteSize",
                                    0,
                                    "contentHash",
                                    "0".repeat(64),
                                    "z",
                                    Map.of(
                                            "storageKey",
                                            "clips/999/source.mp4",
                                            "byteSize",
                                            1,
                                            "contentHash",
                                            "0".repeat(64))))));
            jdbc.update(
                    """
                INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version,
                    status, stage_states_json, created_at, updated_at)
                VALUES (?, 802, ?, 'pipeline-test', 'queued', ?::jsonb, now(), now())
                """,
                    run,
                    (int) (run - 802),
                    mapper.writeValueAsString(Map.of("schemaVersion", "npick.stage_states/v1", "stages", states)));
        }
        String base = "http://127.0.0.1:" + port;
        var http = HttpClient.newHttpClient();
        var denied = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/internal/jobs/claim"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);
        var foreignFleet = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/internal/jobs/claim"))
                        .header("Authorization", "Bearer " + "r".repeat(32))
                        .header("X-Worker-Id", "http-test")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"worker\":{\"workerId\":\"http-test\",\"fleet\":\"prod\"},\"waitSeconds\":0}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(foreignFleet.statusCode()).isEqualTo(403);
        assertThat(foreignFleet.body()).contains("JOB_403_002");
        String defaultPython = System.getProperty("os.name").startsWith("Windows")
                ? "../ai/.venv/Scripts/python.exe"
                : "../ai/.venv/bin/python";
        String python = System.getenv().getOrDefault("NPICK_TEST_PYTHON", defaultPython);
        Path log = ROOT.resolve("probe.log");
        Process probe = new ProcessBuilder(python, "../ai/tests/worker_http_probe.py", base, ROOT.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertThat(probe.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.exitValue()).withFailMessage(Files.readString(log)).isZero();
        } finally {
            if (probe.isAlive()) probe.destroyForcibly();
        }
        for (long run : new long[] {803, 804}) {
            var states = mapper.readTree(jdbc.queryForObject(
                            "SELECT stage_states_json::text FROM npick.pipeline_run WHERE pipeline_run_id=?",
                            String.class,
                            run))
                    .path("stages");
            assertThat(states.path("transcript_selection").path("status").asText())
                    .isEqualTo("succeeded");
            assertThat(states.path("transcript_selection")
                            .path("lastRequestSha256")
                            .asText())
                    .hasSize(64);
            assertThat(states.path("transcript_selection")
                            .path("preparedTranscript")
                            .path("segmentsArtifact")
                            .path("storageKey")
                            .asText())
                    .startsWith("runs/" + run + "/transcript_selection/a1/");
            assertThat(states.path("asr").path("status").asText()).isEqualTo("skipped");
            assertThat(states.path("asr").path("attempts").asInt()).isZero();
            assertThat(states.path("scene_transcript_mapping").path("status").asText())
                    .isEqualTo("pending");
        }
        assertThat(jdbc.queryForObject("SELECT active_pipeline_run_id FROM npick.clip WHERE clip_id=802", Long.class))
                .isNull();
        verifyFencing(base);
    }

    private void verifyFencing(String base) throws Exception {
        var claim = request(
                base,
                "/claim",
                "POST",
                mapper.writeValueAsString(Map.of(
                        "worker",
                        Map.of("workerId", "http-test", "fleet", "local"),
                        "waitSeconds",
                        0,
                        "capabilities",
                        java.util.List.of(Map.of(
                                "stage",
                                "entity_extraction",
                                "stageVersion",
                                "npick.stage.entity_extraction/v1:aaaaaaaa")),
                        "device",
                        Map.of())),
                Map.of());
        assertThat(claim.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(claim.body()).path("data").path("assigned").asBoolean())
                .isFalse();
        // Unsupported stages remain unassigned. Use a separate supported assignment for fencing tests.
        Map<String, Object> freshStates = new LinkedHashMap<>();
        for (String stage : PipelineStages.NAMES)
            freshStates.put(
                    stage,
                    Map.of(
                            "status",
                            "pending",
                            "attempts",
                            0,
                            "expectedStageVersion",
                            "npick.stage." + stage + "/v1:aaaaaaaa"));
        jdbc.update(
                """
                INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version,
                    status, stage_states_json, created_at, updated_at)
                VALUES (805, 802, 3, 'pipeline-test', 'queued', ?::jsonb, now(), now())
                """,
                mapper.writeValueAsString(Map.of("schemaVersion", "npick.stage_states/v1", "stages", freshStates)));
        claim = request(
                base,
                "/claim",
                "POST",
                mapper.writeValueAsString(Map.of(
                        "worker",
                        Map.of("workerId", "http-test", "fleet", "local"),
                        "waitSeconds",
                        0,
                        "capabilities",
                        java.util.List.of(
                                Map.of(
                                        "stage",
                                        "scene_detection",
                                        "stageVersion",
                                        "npick.stage.scene_detection/v1:aaaaaaaa"),
                                Map.of("stage", "future_stage", "stageVersion", "v1")),
                        "device",
                        Map.of())),
                Map.of());
        assertThat(claim.statusCode()).isEqualTo(200);
        var assignment = mapper.readTree(claim.body()).path("data");
        assertThat(assignment.path("assigned").asBoolean()).isTrue();
        String run = assignment.path("job").path("pipelineRunId").asText();
        String lease = assignment.path("lease").path("leaseId").asText();
        String prefix = assignment.path("job").path("outputKeyPrefix").asText();
        String idempotency = assignment.path("job").path("idempotencyKey").asText();
        var result = new LinkedHashMap<String, Object>(mapper.readValue(
                Files.readString(ROOT.resolve("live-result.json")),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        result.put("stage", "scene_detection");
        result.put("leaseId", lease);
        result.put("idempotencyKey", idempotency);
        result.put("output", Map.of("unimplemented", true));
        result.put("artifacts", java.util.List.of());
        result.put(
                "versions",
                Map.of(
                        "stageVersion",
                        "npick.stage.scene_detection/v1:aaaaaaaa",
                        "outputSchemaVersion",
                        "npick.stage.scene_detection.output/v1",
                        "modelVersion",
                        "mock",
                        "promptVersion",
                        "mock"));
        String completionPath = "/" + run + "/stages/scene_detection/complete";
        var invalid = request(
                base,
                completionPath,
                "POST",
                mapper.writeValueAsString(result),
                Map.of("Idempotency-Key", idempotency));
        assertThat(invalid.statusCode()).isEqualTo(400);
        // The domain changed in memory before validation; persistence must roll back together.
        assertThat(jdbc.queryForObject(
                        "SELECT stage_states_json->'stages'->'scene_detection'->>'status' FROM npick.pipeline_run WHERE pipeline_run_id=?",
                        String.class,
                        Long.parseLong(run)))
                .isEqualTo("running");
        assertThat(jdbc.queryForObject(
                        "SELECT lease_id::text FROM npick.pipeline_run WHERE pipeline_run_id=?",
                        String.class,
                        Long.parseLong(run)))
                .isEqualTo(lease);
        var otherWorker = request(
                base,
                "/" + run + "/stages/scene_detection/heartbeat",
                "POST",
                mapper.writeValueAsString(Map.of("leaseId", lease)),
                Map.of("X-Worker-Id", "other-worker"));
        assertThat(otherWorker.statusCode()).isEqualTo(409);
        assertThat(otherWorker.body()).contains("JOB_409_002");
        jdbc.update(
                "UPDATE npick.pipeline_run SET lease_expires_at=now()-interval '1 second' WHERE pipeline_run_id=?",
                Long.parseLong(run));
        var expired = request(
                base,
                completionPath,
                "POST",
                mapper.writeValueAsString(result),
                Map.of("Idempotency-Key", idempotency));
        assertThat(expired.statusCode()).isEqualTo(409);
        assertThat(expired.body()).contains("JOB_409_002");
        var rejectedUpload = request(
                base,
                "/" + run + "/artifacts/" + prefix + "expired.json",
                "PUT",
                "{}",
                Map.of("X-Job-Lease-Id", lease, "X-Content-SHA256", "0".repeat(64)));
        assertThat(rejectedUpload.statusCode()).isEqualTo(409);
        assertThat(ROOT.resolve(prefix + "expired.json")).doesNotExist();
    }

    private HttpResponse<String> request(
            String base, String path, String method, String body, Map<String, String> extra) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + "/api/v1/internal/jobs" + path))
                .header("Authorization", "Bearer " + "t".repeat(32))
                .header("X-Worker-Id", extra.getOrDefault("X-Worker-Id", "http-test"))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        extra.forEach((key, value) -> {
            if (!key.equals("X-Worker-Id")) builder.header(key, value);
        });
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
