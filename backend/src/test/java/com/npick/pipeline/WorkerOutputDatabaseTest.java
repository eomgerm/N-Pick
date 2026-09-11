package com.npick.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.infrastructure.artifact.LocalWorkerArtifactAdapter;
import com.npick.pipeline.infrastructure.persistence.JdbcWorkerStageOutputAdapter;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkerOutputDatabaseTest {
    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path root;

    JdbcWorkerStageOutputAdapter adapter;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties, Database.URL);
    }

    private static class Database {
        static final String URL = create();

        private static String create() {
            String url = NpickPostgres.freshDatabase("worker_output");
            NpickPostgres.migrate(url);
            return url;
        }
    }

    @BeforeEach
    void setup() {
        jdbc.update(
                "INSERT INTO npick.member VALUES (701, 'worker-output', 'unused', 'test', 'reviewer', now(), now())");
        jdbc.update("""
                INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,
                    registered_by_id, created_at, updated_at) VALUES (702, 'archive', 'clips/702/source.mp4', ?, 'none', 701, now(), now())
                """, "a".repeat(64));
        jdbc.update("""
                INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,
                    stage_states_json, created_at, updated_at) VALUES (703, 702, 1, 'pipeline-test', 'running', '{}', now(), now())
                """);
        adapter = new JdbcWorkerStageOutputAdapter(jdbc, new JsonMapper(), new LocalWorkerArtifactAdapter(root));
    }

    static Map<String, Object> body(String stage, Map<String, Object> output) {
        return Map.of(
                "status",
                "succeeded",
                "stage",
                stage,
                "versions",
                Map.of("outputSchemaVersion", "npick.stage." + stage + ".output/v1"),
                "output",
                output,
                "artifacts",
                List.of());
    }

    @Test
    void storesScenesWithAssignedIdsAndDoesNotPublishRun() {
        jdbc.update("""
                INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,
                    stage_states_json, created_at, updated_at) VALUES (704, 702, 2, 'old-version', 'succeeded', '{}', now(), now())
                """);
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id=704, transcript_source='provided' WHERE clip_id=702");
        var ids = adapter.validateAndStore(
                703,
                702,
                "scene_detection",
                "runs/703/scene_detection/a1/",
                body(
                        "scene_detection",
                        Map.of(
                                "scenes",
                                List.of(
                                        Map.of("sceneIndex", 0, "startTimeMs", 0, "endTimeMs", 1000),
                                        Map.of("sceneIndex", 1, "startTimeMs", 1000, "endTimeMs", 2000)),
                                "mediaDurationMs",
                                2000,
                                "frameRate",
                                25)));
        assertThat((List<?>) ids.get("scenes")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.scene WHERE pipeline_run_id=703", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT active_pipeline_run_id FROM npick.clip WHERE clip_id=702", Long.class))
                .isEqualTo(704L);
        assertThat(jdbc.queryForObject("SELECT transcript_source FROM npick.clip WHERE clip_id=702", String.class))
                .isEqualTo("provided");
    }

    @Test
    void preservesRepresentativeOrderAndRejectsInvalidLaterFrameBeforeAnyInsert() throws Exception {
        adapter.validateAndStore(
                703,
                702,
                "scene_detection",
                "runs/703/scene_detection/a1/",
                body(
                        "scene_detection",
                        Map.of(
                                "scenes",
                                List.of(Map.of("sceneIndex", 0, "startTimeMs", 0, "endTimeMs", 2000)),
                                "mediaDurationMs",
                                2000,
                                "frameRate",
                                25)));
        var store = new LocalWorkerArtifactAdapter(root);
        String prefix = "runs/703/frame_extraction/a1/";
        byte[] image = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        String hash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(image));
        List<Map<String, Object>> refs = new java.util.ArrayList<>();
        for (String name : List.of("representative.jpg", "earlier.jpg")) {
            try (var upload = store.prepareUpload(
                    prefix, prefix + name, image.length, hash, new java.io.ByteArrayInputStream(image))) {
                upload.publish();
            }
            refs.add(Map.of(
                    "kind", "keyframe", "storageKey", prefix + name, "byteSize", image.length, "contentHash", hash));
        }
        var valid = Map.<String, Object>of(
                "imageWidth",
                100,
                "imageHeight",
                100,
                "scenes",
                List.of(Map.of(
                        "sceneIndex",
                        0,
                        "representativeTimestampMs",
                        1000,
                        "keyframes",
                        List.of(
                                Map.of(
                                        "sceneIndex",
                                        0,
                                        "timestampMs",
                                        1000,
                                        "storageKey",
                                        prefix + "representative.jpg"),
                                Map.of("sceneIndex", 0, "timestampMs", 0, "storageKey", prefix + "earlier.jpg")))));
        var result = new java.util.LinkedHashMap<>(body("frame_extraction", valid));
        result.put("artifacts", refs.subList(0, 1));
        assertThatThrownBy(() -> adapter.validateAndStore(703, 702, "frame_extraction", prefix, result))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.keyframe", Long.class))
                .isZero();
        result.put("artifacts", refs);
        adapter.validateAndStore(703, 702, "frame_extraction", prefix, result);
        assertThat(jdbc.queryForList("SELECT timestamp_ms FROM npick.keyframe ORDER BY keyframe_id", Long.class))
                .containsExactly(1000L, 0L);
    }

    @Test
    void malformedLaterSceneDoesNotPartiallyStoreEarlierScene() {
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "scene_detection",
                        "runs/703/scene_detection/a1/",
                        body(
                                "scene_detection",
                                Map.of(
                                        "scenes",
                                        List.of(
                                                Map.of("sceneIndex", 0, "startTimeMs", 0, "endTimeMs", 1000),
                                                Map.of("sceneIndex", 1, "startTimeMs", 900, "endTimeMs", 2000)),
                                        "mediaDurationMs",
                                        2000,
                                        "frameRate",
                                        25))))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.scene WHERE pipeline_run_id=703", Long.class))
                .isZero();
    }

    @Test
    void rejectsForeignRunAndUnimplementedStorage() {
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703, 999, "asr", "runs/703/asr/a1/", body("asr", Map.of("segments", List.of()))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "scene_transcript_mapping",
                        "runs/703/scene_transcript_mapping/a1/",
                        body("scene_transcript_mapping", Map.of("scenes", List.of()))))
                .isInstanceOf(BusinessException.class);
        adapter.validateAndStore(703, 702, "asr", "runs/703/asr/a1/", body("asr", Map.of("segments", List.of())));
    }

    @Test
    void asrRejectsWrongSourceAndDuplicateSegmentIds() {
        var valid = Map.of("segmentId", "asr-1", "sourceDetail", "asr", "s", 0, "e", 100, "t", "speech");
        var wrong = Map.of("segmentId", "asr-2", "sourceDetail", "uploaded", "s", 100, "e", 200, "t", "subtitle");
        for (var segments : List.of(List.of(valid, valid), List.of(valid, wrong))) {
            assertThatThrownBy(() -> adapter.validateAndStore(
                            703, 702, "asr", "runs/703/asr/a1/", body("asr", Map.of("segments", segments))))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e -> assertThat(e.errorCode().code()).isEqualTo("JOB_400_001"));
        }
        adapter.validateAndStore(703, 702, "asr", "runs/703/asr/a1/", body("asr", Map.of("segments", List.of(valid))));
    }
}
