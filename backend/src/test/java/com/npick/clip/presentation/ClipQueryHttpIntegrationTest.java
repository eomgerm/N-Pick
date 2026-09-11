package com.npick.clip.presentation;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.npick.pipeline.domain.model.PipelineStages;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 PostgreSQL·파일 artifact·세션 로그인·HTTP·OpenAPI를 연결한다. AI는 실행하지 않는다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "server.servlet.session.cookie.secure=false") // Test server uses loopback HTTP, production uses HTTPS.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ClipQueryHttpIntegrationTest {
    static final Path ROOT =
            Path.of("build/clip-query-http-" + UUID.randomUUID()).toAbsolutePath();

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    PasswordEncoder encoder;

    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties, Database.URL);
        properties.add("npick.clip-registration.media-root", ROOT::toString);
    }

    private static class Database {
        static final String URL = create();

        static String create() {
            String url = NpickPostgres.freshDatabase("clip_query_http");
            NpickPostgres.migrate(url);
            return url;
        }
    }

    @BeforeAll
    void login() throws Exception {
        jdbc.update(
                "INSERT INTO npick.member VALUES (901, 'clip-query-http', ?, '검수자', 'REVIEWER', now(), now())",
                encoder.encode("query-test"));
        assertThat(request("GET", "/api/v1/clips", null).statusCode()).isEqualTo(401);
        request("GET", "/api/v1/auth/csrf", null);
        String csrf = cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN"))
                .findFirst()
                .orElseThrow()
                .getValue();
        var response = client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                        .header("X-XSRF-TOKEN", csrf)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"clip-query-http\",\"password\":\"query-test\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void stagesAsrOutcomesAndActiveRunStayDistinct() throws Exception {
        // Actual stored statuses, including optional failure and a latest fatal reprocessing failure.
        var stages = stages();
        stages.put("scene_detection", state("succeeded"));
        stages.put("frame_extraction", state("succeeded"));
        stages.put("vlm_metadata", state("running"));
        fixture(101, 1001, "running", stages);
        JsonNode progressing = detail(101);
        assertThat(progressing.at("/processing_details/stages/2/status").asString())
                .isEqualTo("running");
        assertThat(progressing.at("/processing_details/stages/5/status").asString())
                .isEqualTo("pending");
        assertThat(progressing
                        .at("/processing_details/transcript/asr_segment_count")
                        .isNull())
                .isTrue();

        var covered = stages();
        covered.put(
                "transcript_selection",
                Map.of(
                        "status",
                        "succeeded",
                        "attempts",
                        1,
                        "output",
                        Map.of("transcript", Map.of("asrRequired", false, "reasonCode", "SUBTITLE_COVERED"))));
        covered.put("asr", Map.of("status", "skipped", "attempts", 0, "reasonCode", "SUBTITLE_COVERED"));
        fixture(102, 1002, "succeeded", covered);
        JsonNode skipped = detail(102).path("processing_details");
        assertThat(skipped.at("/transcript/asr_status").asString()).isEqualTo("skipped");
        assertThat(skipped.at("/transcript/asr_reason").asString()).isEqualTo("SUBTITLE_COVERED");
        assertThat(skipped.at("/transcript/asr_required").booleanValue()).isFalse();
        assertThat(skipped.path("missing_channels").isEmpty()).isTrue();

        for (int i = 0; i < 2; i++) {
            var empty = stages();
            var output = new LinkedHashMap<String, Object>();
            output.put("segments", List.of());
            if (i == 0) output.put("reasonCode", "NO_SPEECH_DETECTED");
            empty.put("asr", Map.of("status", "succeeded", "attempts", 1, "output", output));
            fixture(103 + i, 1003 + i, "succeeded", empty);
            JsonNode transcript = detail(103 + i).at("/processing_details/transcript");
            assertThat(transcript.path("asr_status").asString()).isEqualTo("succeeded");
            assertThat(transcript.path("asr_segment_count").intValue()).isZero();
            if (i == 0) assertThat(transcript.path("asr_reason").asString()).isEqualTo("NO_SPEECH_DETECTED");
            else assertThat(transcript.path("asr_reason").isNull()).isTrue();
            assertThat(transcript.path("used_sources").isNull()).isTrue();
        }
        var failed = stages();
        failed.put(
                "asr",
                Map.of(
                        "status",
                        "failed",
                        "attempts",
                        1,
                        "errorCode",
                        "MODEL_TIMEOUT",
                        "errorRetryable",
                        true,
                        "error",
                        Map.of("code", "MODEL_TIMEOUT", "retryable", true, "message", "/srv/private/token")));
        fixture(105, 1005, "succeeded", failed);
        JsonNode degraded = detail(105).path("processing_details");
        assertThat(degraded.path("missing_channels").toString()).isEqualTo("[\"asr\"]");
        assertThat(degraded.path("failed_stages").toString()).isEqualTo("[\"asr\"]");
        assertThat(degraded.at("/stages/5/automatic_retryable").booleanValue()).isFalse();
        assertThat(degraded.path("retryable").isNull()).isTrue();
        assertThat(degraded.toString()).doesNotContain("/srv", "errorRetryable", "message", "token");

        var noAdapter = stages();
        noAdapter.put("asr", Map.of("status", "skipped", "attempts", 0, "errorCode", "NO_ADAPTER"));
        fixture(106, 1006, "running", noAdapter);
        assertThat(detail(106).at("/processing_details/stages/5/error_code").asString())
                .isEqualTo("NO_ADAPTER");
        assertThat(detail(106).at("/processing_details/missing_channels").toString())
                .isEqualTo("[\"asr\"]");

        var fatal = stages();
        fatal.put("frame_extraction", Map.of("status", "failed", "attempts", 1, "errorCode", "UNSUPPORTED_MEDIA"));
        fixture(107, 1007, "failed", fatal);
        jdbc.update(
                "INSERT INTO npick.pipeline_run (pipeline_run_id,clip_id,processing_no,pipeline_version,status,stage_states_json,created_at,updated_at) VALUES (999,107,2,'old','succeeded','{}',now()-interval '1 day',now())");
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id=999, transcript_source='provided' WHERE clip_id=107");
        JsonNode reprocessed = detail(107);
        assertThat(reprocessed.at("/clip/search_available").booleanValue()).isTrue();
        assertThat(reprocessed.at("/clip/active_pipeline_run_id").asString()).isEqualTo("999");
        assertThat(reprocessed.at("/clip/latest_run/status").asString()).isEqualTo("failed");
        assertThat(reprocessed.at("/processing_details/pipeline_run_id").asString())
                .isEqualTo("1007");
        assertThat(reprocessed.path("default_transcript_source").asString()).isEqualTo("provided");
        assertThat(reprocessed.at("/processing_details/failed_stages").toString())
                .contains("frame_extraction");
        JsonNode list = body(request("GET", "/api/v1/clips?size=100", null)).path("data");
        assertThat(list.path("items").size()).isGreaterThanOrEqualTo(7);
        assertThat(list.toString()).doesNotContain("stage_states", "/srv", "raw_response");
    }

    @Test
    void adoptedSourcesUseVerifiedDecisionsAndNeverRawAsrCandidates() throws Exception {
        long run = 2001;
        var stages = stages();
        var originals = List.of(segment("u", "uploaded"), segment("e", "embedded"), segment("a", "asr"));
        var selectedRef = artifact(
                run,
                "transcript_segments",
                Map.of("schemaVersion", "npick.transcript.segments/v1", "segments", originals));
        var decisionsRef = artifact(
                run,
                "transcript_decisions",
                Map.of(
                        "schemaVersion",
                        "npick.transcript.decisions/v1",
                        "segmentsArtifact",
                        selectedRef,
                        "decisions",
                        List.of(
                                decision("u", true, "PREFERRED_SUBTITLE"),
                                decision("e", false, "OVERLAPS_HIGHER_PRIORITY"),
                                decision("a", true, "ASR_SUPPLEMENT"))));
        stages.put(
                "scene_transcript_mapping",
                Map.of(
                        "status",
                        "succeeded",
                        "attempts",
                        1,
                        "output",
                        Map.of(
                                "transcript",
                                Map.of("segmentsArtifact", selectedRef, "decisionsArtifact", decisionsRef))));
        stages.put(
                "transcript_selection",
                Map.of(
                        "status",
                        "succeeded",
                        "attempts",
                        1,
                        "output",
                        Map.of("transcript", Map.of("asrRequired", true, "reasonCode", "UNCOVERED_RANGES"))));
        stages.put(
                "asr",
                Map.of(
                        "status",
                        "succeeded",
                        "attempts",
                        1,
                        "output",
                        Map.of("segments", List.of(segment("raw", "asr")), "raw_response", "private-model-answer")));
        fixture(201, run, "succeeded", stages);
        JsonNode transcript = detail(201).at("/processing_details/transcript");
        assertThat(transcript.path("used_sources").toString()).isEqualTo("[\"uploaded\",\"asr\"]");
        assertThat(transcript.path("representative_source").asString()).isEqualTo("provided");
        assertThat(transcript.path("selection_stage").asString()).isEqualTo("scene_transcript_mapping");
        assertThat(transcript.path("selection_reason").asString()).isEqualTo("UNCOVERED_RANGES");
        assertThat(detail(201).toString())
                .doesNotContain(
                        "private-model",
                        "secret-dialogue",
                        "storageKey",
                        "contentHash",
                        "raw_response",
                        "conflictsWith");
        // Integrity failure must remain unknown, not silently become no dialogue.
        Files.writeString(ROOT.resolve((String) decisionsRef.get("storageKey")), "corrupt");
        assertThat(detail(201)
                        .at("/processing_details/transcript/record_status")
                        .asString())
                .isEqualTo("unavailable");
        assertThat(detail(201).at("/processing_details/transcript/used_sources").isNull())
                .isTrue();
    }

    @Test
    void representativeSourcesAndEmptySelectionFollowOnlyAdoptedSnapshots() throws Exception {
        List<List<String>> cases = List.of(List.of("uploaded"), List.of("embedded"), List.of("asr"), List.of());
        for (int i = 0; i < cases.size(); i++) {
            long run = 4001 + i;
            var originals = new ArrayList<Map<String, Object>>();
            var decisions = new ArrayList<Map<String, Object>>();
            for (String source : cases.get(i)) {
                originals.add(segment(source, source));
                decisions.add(decision(source, true, source.equals("asr") ? "ASR_SUPPLEMENT" : "PREFERRED_SUBTITLE"));
            }
            var segmentsRef = artifact(
                    run,
                    "transcript_segments",
                    Map.of("schemaVersion", "npick.transcript.segments/v1", "segments", originals));
            var decisionsRef = artifact(
                    run,
                    "transcript_decisions",
                    Map.of(
                            "schemaVersion",
                            "npick.transcript.decisions/v1",
                            "segmentsArtifact",
                            segmentsRef,
                            "decisions",
                            decisions));
            var states = stages();
            // A completed raw ASR output without a later adopted decision must not add asr to used_sources.
            states.put(
                    "asr",
                    Map.of(
                            "status",
                            "succeeded",
                            "attempts",
                            1,
                            "output",
                            Map.of("segments", List.of(segment("candidate", "asr")))));
            states.put(
                    "scene_transcript_mapping",
                    Map.of(
                            "status",
                            "succeeded",
                            "attempts",
                            1,
                            "output",
                            Map.of(
                                    "transcript",
                                    Map.of("segmentsArtifact", segmentsRef, "decisionsArtifact", decisionsRef))));
            fixture(401 + i, run, "succeeded", states);
            JsonNode transcript = detail(401 + i).at("/processing_details/transcript");
            assertThat(transcript.path("record_status").asString()).isEqualTo("available");
            assertThat(transcript.path("used_sources").toString()).isEqualTo(mapper.writeValueAsString(cases.get(i)));
            assertThat(transcript.path("representative_source").asString())
                    .isEqualTo(i < 2 ? "provided" : i == 2 ? "asr" : "none");
            assertThat(transcript.path("adoption_reasons").toString())
                    .isEqualTo(i < 2 ? "[\"PREFERRED_SUBTITLE\"]" : i == 2 ? "[\"ASR_SUPPLEMENT\"]" : "[]");
        }
    }

    @Test
    void legacyMissingAndForeignVersionsAreNotInvented() throws Exception {
        fixture(301, 3001, "running", stages());
        jdbc.update(
                "UPDATE npick.pipeline_run SET stage_states_json=?::jsonb WHERE pipeline_run_id=3001",
                "{\"scene_detect\":{\"status\":\"succeeded\",\"attempts\":1},\"asr\":{\"status\":\"skipped\",\"error_code\":\"NO_ADAPTER\"}}");
        JsonNode legacy = detail(301).path("processing_details");
        assertThat(legacy.path("record_status").asString()).isEqualTo("partial");
        assertThat(legacy.at("/stages/0/status").asString()).isEqualTo("succeeded");
        assertThat(legacy.at("/stages/1/status").asString()).isEqualTo("unknown");
        assertThat(legacy.at("/stages/5/error_code").asString()).isEqualTo("NO_ADAPTER");
        jdbc.update(
                "UPDATE npick.pipeline_run SET stage_states_json='{\"schemaVersion\":\"future/v2\",\"stages\":{}}' WHERE pipeline_run_id=3001");
        JsonNode future = detail(301).path("processing_details");
        assertThat(future.path("record_status").asString()).isEqualTo("unsupported_version");
        assertThat(future.path("missing_channels").isNull()).isTrue();
        assertThat(future.path("retryable").isNull()).isTrue();
        jdbc.update("UPDATE npick.pipeline_run SET stage_states_json='{}' WHERE pipeline_run_id=3001");
        assertThat(detail(301).at("/processing_details/record_status").asString())
                .isEqualTo("unavailable");
    }

    @Test
    void malformedAndPartialRecordsRemainReadableWithoutInventingChannelCompleteness() throws Exception {
        fixture(601, 6001, "running", stages());
        for (String version : List.of("{}", "[]", "null", "7", "true")) {
            jdbc.update(
                    "UPDATE npick.pipeline_run SET stage_states_json=?::jsonb WHERE pipeline_run_id=6001",
                    "{\"schemaVersion\":" + version + ",\"stages\":{}}");
            JsonNode details = detail(601).path("processing_details");
            assertThat(details.path("record_status").asString()).isEqualTo("unavailable");
            assertThat(details.path("missing_channels").isNull()).isTrue();
        }
        var partial = new LinkedHashMap<String, Object>();
        partial.put("scene_detection", state("succeeded"));
        partial.put("transcript_selection", Map.of("status", Map.of()));
        partial.put("scene_transcript_mapping", Map.of("status", List.of()));
        for (boolean failed : List.of(false, true)) {
            if (failed) partial.put("asr", Map.of("status", "failed", "errorCode", "ASR_FAILED"));
            jdbc.update(
                    "UPDATE npick.pipeline_run SET stage_states_json=?::jsonb WHERE pipeline_run_id=6001",
                    mapper.writeValueAsString(Map.of("schemaVersion", "npick.stage_states/v1", "stages", partial)));
            JsonNode details = detail(601).path("processing_details");
            assertThat(details.path("record_status").asString()).isEqualTo("partial");
            assertThat(details.path("missing_channels").isNull()).isTrue();
            assertThat(details.path("failed_stages").toString()).isEqualTo(failed ? "[\"asr\"]" : "[]");
            assertThat(details.at("/stages/4/status").asString()).isEqualTo("unknown");
            assertThat(details.at("/transcript/used_sources").isNull()).isTrue();
        }
    }

    @Test
    void mandatorySkippedStageReportsTheStoredRunFailureWithoutPretendingExecutionSucceeded() throws Exception {
        var initial = stages();
        fixture(602, 6002, "queued", initial);
        var now = java.time.Instant.parse("2026-09-11T05:00:00Z");
        var run = new com.npick.pipeline.domain.model.PipelineRun(
                new com.npick.pipeline.domain.model.PipelineRun.Snapshot(
                        6002,
                        602,
                        1,
                        "test",
                        "queued",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("schemaVersion", "npick.stage_states/v1", "stages", initial)));
        run.claim("scene_detection", "test", UUID.randomUUID(), Map.of(), now);
        run.complete(
                "scene_detection",
                Map.of(
                        "status",
                        "skipped",
                        "attempt",
                        1,
                        "idempotencyKey",
                        run.idempotencyKey("scene_detection"),
                        "versions",
                        Map.of("stageVersion", "unknown"),
                        "startedAt",
                        now.toString(),
                        "finishedAt",
                        now.toString(),
                        "error",
                        Map.of("code", "NO_ADAPTER", "retryable", false)),
                "test",
                now);
        assertThat(run.snapshot().status()).isEqualTo("failed");
        saveRecord(run);
        JsonNode detail = detail(602);
        assertThat(detail.at("/clip/latest_run/status").asString()).isEqualTo("failed");
        assertThat(detail.at("/processing_details/failed_stages").toString()).isEqualTo("[\"scene_detection\"]");
        assertThat(detail.at("/processing_details/stages/0/status").asString()).isEqualTo("skipped");
        assertThat(detail.at("/processing_details/stages/0/error_code").asString())
                .isEqualTo("NO_ADAPTER");
        assertThat(detail.at("/processing_details/stages/0/automatic_retryable").booleanValue())
                .isFalse();
    }

    @Test
    void mergedRetryRecordsShowPendingRunningRecoveryAndExhaustionWithoutRecomputingPolicy() throws Exception {
        for (boolean finalFailure : List.of(false, true)) {
            long clipId = finalFailure ? 502 : 501;
            long runId = finalFailure ? 5002 : 5001;
            var initial = stages();
            for (String name : PipelineStages.NAMES.subList(0, PipelineStages.NAMES.indexOf("asr"))) {
                initial.put(name, state("succeeded"));
            }
            fixture(clipId, runId, "running", initial);
            var now = java.time.Instant.parse("2026-09-11T05:00:00Z");
            var run = new com.npick.pipeline.domain.model.PipelineRun(
                    new com.npick.pipeline.domain.model.PipelineRun.Snapshot(
                            runId,
                            clipId,
                            1,
                            "test",
                            "running",
                            null,
                            now,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            Map.of("schemaVersion", "npick.stage_states/v1", "stages", initial)));
            var policy = new com.npick.pipeline.domain.model.StageRetrySettings(
                    Map.of("asr", 2), java.util.Set.of("ASR_FAILED"));
            run.claim("asr", "private-worker", UUID.randomUUID(), Map.of(), now, policy);
            completeAttempt(run, true, now.plusSeconds(1), policy);
            saveRecord(run);
            JsonNode waiting = detail(clipId).path("processing_details");
            assertThat(waiting.at("/stages/5/automatic_retryable").booleanValue())
                    .isTrue();
            assertThat(waiting.at("/stages/5/max_attempts").intValue()).isEqualTo(2);
            assertThat(waiting.at("/stages/5/failed_attempts/0/attempt").intValue())
                    .isEqualTo(1);
            assertThat(waiting.at("/stages/5/failed_attempts/0/error_code").asString())
                    .isEqualTo("ASR_FAILED");
            assertThat(waiting.at("/stages/5/failed_attempts/0/finished_at").asString())
                    .isEqualTo(now.plusSeconds(1).toString());
            assertThat(waiting.path("failed_stages").isEmpty()).isTrue();
            assertThat(waiting.path("missing_channels").isEmpty()).isTrue();
            assertThat(waiting.path("retryable").isNull()).isTrue();

            // The stored policy wins over the changed profile, and the consumed scheduling flag must not stay true.
            var changed = new com.npick.pipeline.domain.model.StageRetrySettings(
                    Map.of("asr", 99), java.util.Set.of("ASR_FAILED"));
            run.claim("asr", "private-worker", UUID.randomUUID(), Map.of(), now.plusSeconds(2), changed);
            saveRecord(run);
            JsonNode running = detail(clipId).at("/processing_details/stages/5");
            assertThat(running.path("status").asString()).isEqualTo("running");
            assertThat(running.path("automatic_retryable").booleanValue()).isFalse();
            assertThat(running.path("attempts").intValue()).isEqualTo(2);
            assertThat(running.path("max_attempts").intValue()).isEqualTo(2);
            completeAttempt(run, finalFailure, now.plusSeconds(3), changed);
            saveRecord(run);
            JsonNode completed = detail(clipId).path("processing_details");
            assertThat(completed.at("/stages/5/status").asString()).isEqualTo(finalFailure ? "failed" : "succeeded");
            assertThat(completed.at("/stages/5/automatic_retryable").booleanValue())
                    .isFalse();
            assertThat(completed.at("/stages/5/failed_attempts").size()).isEqualTo(1);
            assertThat(completed.path("missing_channels").toString()).isEqualTo(finalFailure ? "[\"asr\"]" : "[]");
            assertThat(completed.toString())
                    .doesNotContain(
                            "private-worker",
                            "/srv",
                            "rawResponse",
                            "secret",
                            "idempotencyKey",
                            "leaseId",
                            "requestSha256");
            assertThat(mapper.readTree(jdbc.queryForObject(
                            "SELECT stage_states_json::text FROM npick.pipeline_run WHERE pipeline_run_id=?",
                            String.class,
                            runId)))
                    .isEqualTo(mapper.readTree(
                            mapper.writeValueAsString(run.snapshot().stageStates())));
        }
    }

    private void completeAttempt(
            com.npick.pipeline.domain.model.PipelineRun run,
            boolean failed,
            java.time.Instant at,
            com.npick.pipeline.domain.model.StageRetrySettings policy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", failed ? "failed" : "succeeded");
        result.put("attempt", run.state("asr").get("attempts"));
        result.put("idempotencyKey", run.idempotencyKey("asr"));
        result.put("versions", Map.of("stageVersion", "unknown"));
        result.put("startedAt", at.minusSeconds(1).toString());
        result.put("finishedAt", at.toString());
        result.put("output", failed ? null : Map.of("segments", List.of()));
        result.put(
                "error",
                failed
                        ? Map.of(
                                "code",
                                "ASR_FAILED",
                                "retryable",
                                true,
                                "message",
                                "/srv/secret",
                                "detail",
                                Map.of("rawResponse", "secret"))
                        : null);
        run.complete("asr", result, "private-request-hash", at, policy);
    }

    private void saveRecord(com.npick.pipeline.domain.model.PipelineRun run) {
        jdbc.update(
                "UPDATE npick.pipeline_run SET status=?,stage_states_json=?::jsonb WHERE pipeline_run_id=?",
                run.snapshot().status(),
                mapper.writeValueAsString(run.snapshot().stageStates()),
                run.snapshot().id());
    }

    @Test
    void swaggerSchemaMatchesPublicSnakeCaseAndTypedDetails() throws Exception {
        JsonNode spec = body(request("GET", "/v3/api-docs", null));
        assertThat(spec.at("/paths/~1api~1v1~1clips/get").isObject()).isTrue();
        assertThat(spec.at("/paths/~1api~1v1~1clips~1{id}/get").isObject()).isTrue();
        JsonNode schemas = spec.at("/components/schemas");
        assertThat(schemas.path("ClipDetailResponse").path("properties").has("processing_details"))
                .isTrue();
        JsonNode detailProperties = schemas.path("ProcessingDetailsResponse").path("properties");
        assertThat(detailProperties.has("pipeline_run_id")).isTrue();
        assertThat(detailProperties.has("missing_channels")).isTrue();
        assertThat(detailProperties.path("stages").path("items").has("$ref")).isTrue();
        assertThat(schemas.path("StageResponse").path("properties").has("automatic_retryable"))
                .isTrue();
        assertThat(schemas.path("StageResponse").path("properties").has("max_attempts"))
                .isTrue();
        assertThat(schemas.path("StageResponse")
                        .path("properties")
                        .path("failed_attempts")
                        .path("items")
                        .has("$ref"))
                .isTrue();
        assertThat(schemas.path("TranscriptResponse").path("properties").has("used_sources"))
                .isTrue();
        assertThat(request("GET", "/swagger-ui/index.html", null).statusCode()).isEqualTo(200);
    }

    private void fixture(long clip, long run, String status, Map<String, Object> stages) {
        jdbc.update(
                "INSERT INTO npick.clip (clip_id,source_type,storage_key,content_hash,title,transcript_source,registered_by_id,created_at,updated_at) VALUES (?,'archive','private/video',?,'조회 검증','none',901,now(),now())",
                clip,
                Long.toString(clip));
        jdbc.update(
                "INSERT INTO npick.pipeline_run (pipeline_run_id,clip_id,processing_no,pipeline_version,status,stage_states_json,created_at,updated_at) VALUES (?,?,1,'test',?,?::jsonb,now(),now())",
                run,
                clip,
                status,
                mapper.writeValueAsString(Map.of("schemaVersion", "npick.stage_states/v1", "stages", stages)));
    }

    private Map<String, Object> stages() {
        Map<String, Object> values = new LinkedHashMap<>();
        PipelineStages.NAMES.forEach(name -> values.put(name, state("pending")));
        return values;
    }

    private Map<String, Object> state(String status) {
        return Map.of("status", status, "attempts", status.equals("pending") ? 0 : 1);
    }

    private Map<String, Object> segment(String id, String source) {
        return Map.of("segmentId", id, "s", 0, "e", 1000, "t", "secret-dialogue", "sourceDetail", source);
    }

    private Map<String, Object> decision(String id, boolean selected, String reason) {
        return Map.of(
                "segmentId",
                id,
                "selected",
                selected,
                "reasonCode",
                reason,
                "conflictsWith",
                selected ? List.of() : List.of("u"));
    }

    private Map<String, Object> artifact(long run, String kind, Object document) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(document);
        String key = "runs/" + run + "/scene_transcript_mapping/a1/" + kind + ".json";
        Files.createDirectories(ROOT.resolve(key).getParent());
        Files.write(ROOT.resolve(key), bytes);
        return Map.of(
                "kind",
                kind,
                "storageKey",
                key,
                "byteSize",
                bytes.length,
                "contentHash",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private JsonNode detail(long id) throws Exception {
        return body(request("GET", "/api/v1/clips/" + id, null)).path("data");
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }
}
