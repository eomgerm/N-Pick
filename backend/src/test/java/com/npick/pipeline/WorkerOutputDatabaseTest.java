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

    // ── text_embedding · indexing (S15P21A501-183) ──────────────────────

    private static final int DIMENSION = 1024;

    private void storeTwoScenes() {
        adapter.validateAndStore(
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
    }

    /** {@code declared} 는 문서가 말하는 차원, {@code length} 는 벡터가 실제로 가진 성분 수다. */
    private static String document(int declared, int length, int... sceneIndexes) {
        String vector = "[" + "0.1,".repeat(length - 1) + "0.1]";
        List<String> scenes = new java.util.ArrayList<>();
        for (int index : sceneIndexes)
            scenes.add("{\"sceneIndex\":" + index + ",\"vector\":" + vector + ",\"sourceText\":\"광안대교\"}");
        return "{\"schemaVersion\":\"npick.scene.embeddings/v1\",\"dimension\":" + declared + ",\"scenes\":["
                + String.join(",", scenes) + "]}";
    }

    private Map<String, Object> embeddingResult(
            String document, int embeddedCount, List<Integer> skipped, boolean register) throws Exception {
        return embeddingResult(document, DIMENSION, embeddedCount, skipped, register);
    }

    private Map<String, Object> embeddingResult(
            String document, int declaredDimension, int embeddedCount, List<Integer> skipped, boolean register)
            throws Exception {
        String prefix = "runs/703/text_embedding/a1/";
        byte[] bytes = document.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        // 한 테스트가 여러 변형을 올린다. 같은 키에 다른 본문은 저장소가 거절하므로 본문으로 가른다.
        String key = prefix + "embeddings-" + hash.substring(0, 8) + ".json";
        try (var upload = new LocalWorkerArtifactAdapter(root)
                .prepareUpload(prefix, key, bytes.length, hash, new java.io.ByteArrayInputStream(bytes))) {
            upload.publish();
        }
        var ref = Map.<String, Object>of(
                "kind", "scene_embeddings", "storageKey", key, "byteSize", bytes.length, "contentHash", hash);
        var result = new java.util.LinkedHashMap<>(body(
                "text_embedding",
                Map.of(
                        "embeddingsArtifact",
                        ref,
                        "dimension",
                        declaredDimension,
                        "embeddedCount",
                        embeddedCount,
                        "skippedSceneIndexes",
                        skipped)));
        result.put("artifacts", register ? List.of(ref) : List.of());
        return result;
    }

    private static Map<String, Object> summary(int sceneCount) {
        return Map.of(
                "sceneCount",
                sceneCount,
                "captionedScenes",
                0,
                "dialogueScenes",
                0,
                "ocrScenes",
                0,
                "embeddedScenes",
                0);
    }

    private long embeddedScenes() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM npick.scene WHERE pipeline_run_id=703 AND embedding IS NOT NULL", Long.class);
    }

    @Test
    void storesSceneEmbeddingsFromTheUploadedArtifact() throws Exception {
        storeTwoScenes();

        var ids = adapter.validateAndStore(
                703,
                702,
                "text_embedding",
                "runs/703/text_embedding/a1/",
                embeddingResult(document(DIMENSION, DIMENSION, 0), 1, List.of(1), true));

        // 워커는 scene_index 로 말한다. DB ID 발급은 scene_detection 이 이미 했다.
        assertThat(ids).isEmpty();
        assertThat(embeddedScenes()).isEqualTo(1);
    }

    @Test
    void rejectsAVectorWhoseLengthDiffersFromTheDeclaredDimension() throws Exception {
        storeTwoScenes();

        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "text_embedding",
                        "runs/703/text_embedding/a1/",
                        embeddingResult(document(DIMENSION, DIMENSION - 1, 0), 1, List.of(1), true)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("JOB_400_001"));
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void rejectsAnEmbeddingsArtifactThatWasNotDeclared() throws Exception {
        storeTwoScenes();

        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "text_embedding",
                        "runs/703/text_embedding/a1/",
                        embeddingResult(document(DIMENSION, DIMENSION, 0), 1, List.of(1), false)))
                .isInstanceOf(BusinessException.class);
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void rejectsSceneIndexesThatAreOutOfRangeRepeatedOrBothEmbeddedAndSkipped() throws Exception {
        storeTwoScenes();

        // 범위 밖 · 중복 · 임베딩과 생략에 동시에 등장
        for (var invalid : List.of(
                embeddingResult(document(DIMENSION, DIMENSION, 2), 1, List.of(), true),
                embeddingResult(document(DIMENSION, DIMENSION, 0, 0), 2, List.of(), true),
                embeddingResult(document(DIMENSION, DIMENSION, 0), 1, List.of(0), true)))
            assertThatThrownBy(() -> adapter.validateAndStore(
                            703, 702, "text_embedding", "runs/703/text_embedding/a1/", invalid))
                    .isInstanceOf(BusinessException.class);
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void rejectsAnEmbeddedCountThatDisagreesWithTheArtifact() throws Exception {
        storeTwoScenes();

        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "text_embedding",
                        "runs/703/text_embedding/a1/",
                        embeddingResult(document(DIMENSION, DIMENSION, 0), 2, List.of(1), true)))
                .isInstanceOf(BusinessException.class);
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void indexingStoresNothingAndRejectsASummaryThatMiscountsTheScenes() {
        storeTwoScenes();

        assertThatThrownBy(() -> adapter.validateAndStore(
                        703, 702, "indexing", "runs/703/indexing/a1/", body("indexing", summary(3))))
                .isInstanceOf(BusinessException.class);
        assertThat(adapter.validateAndStore(
                        703, 702, "indexing", "runs/703/indexing/a1/", body("indexing", summary(2))))
                .isEmpty();
    }

    @Test
    void supportsTheTwoNewlyWiredStages() {
        // `supports()` 가 거짓이면 WorkerExecutionBinding 이 claim capabilities 에서 지운다.
        assertThat(adapter.supports("text_embedding")).isTrue();
        assertThat(adapter.supports("indexing")).isTrue();
    }

    @Test
    void rejectsADimensionThatDoesNotMatchTheStoredColumn() throws Exception {
        storeTwoScenes();

        // 세 값(payload·artifact·벡터 길이)이 **서로** 맞기만 하면 통과하던 자리다.
        // 셋 다 워커가 만드는 값이라, 설정만 768 로 바꾸고 마이그레이션을 두면 여기를 지나
        // vector(1024) 컬럼이 트랜잭션을 SQL 오류로 끊는다 — 워커가 받는 것은 400 이 아니라 500 이다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "text_embedding",
                        "runs/703/text_embedding/a1/",
                        embeddingResult(document(768, 768, 0), 768, 1, List.of(1), true)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("JOB_400_001"));
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void rejectsASkippedIndexTooLargeForALong() throws Exception {
        storeTwoScenes();

        var result = embeddingResult(document(DIMENSION, DIMENSION, 0), 1, List.of(1), true);
        @SuppressWarnings("unchecked")
        var output = new java.util.LinkedHashMap<String, Object>((Map<String, Object>) result.get("output"));
        output.put("skippedSceneIndexes", List.of(new java.math.BigInteger("99999999999999999999")));
        result.put("output", output);

        assertThatThrownBy(() ->
                        adapter.validateAndStore(703, 702, "text_embedding", "runs/703/text_embedding/a1/", result))
                .isInstanceOf(BusinessException.class);
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void writesTheVectorToTheSceneThatTheIndexNamesNotTheFirstRow() throws Exception {
        storeTwoScenes();

        adapter.validateAndStore(
                703,
                702,
                "text_embedding",
                "runs/703/text_embedding/a1/",
                embeddingResult(document(DIMENSION, DIMENSION, 1), 1, List.of(0), true));

        // sceneIndex 1 은 두 번째 장면이다. 한 칸 밀리면 검색 결과가 미묘하게 어긋나기만 한다.
        assertThat(jdbc.queryForList(
                        "SELECT start_time_ms FROM npick.scene WHERE pipeline_run_id=703 AND embedding IS NOT NULL",
                        Long.class))
                .containsExactly(1000L);
    }

    @Test
    void rejectsAVectorComponentThatIsNotFinite() throws Exception {
        storeTwoScenes();

        // NaN·inf 가 저장되면 pgvector 의 코사인 거리가 정의되지 않아 그 장면이 모든 질의에서 빠진다.
        String overflow = "{\"schemaVersion\":\"npick.scene.embeddings/v1\",\"dimension\":" + DIMENSION
                + ",\"scenes\":[{\"sceneIndex\":0,\"vector\":[1e400" + ",0.1".repeat(DIMENSION - 1)
                + "],\"sourceText\":\"광안대교\"}]}";

        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "text_embedding",
                        "runs/703/text_embedding/a1/",
                        embeddingResult(overflow, DIMENSION, 1, List.of(1), true)))
                .isInstanceOf(BusinessException.class);
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void rejectsAnArtifactWithTheWrongSchemaVersionOrDimension() throws Exception {
        storeTwoScenes();

        String wrongSchema = document(DIMENSION, DIMENSION, 0).replace("npick.scene.embeddings/v1", "npick.other/v1");
        // 문서가 말하는 차원과 payload 가 말하는 차원이 다르다. 벡터 길이는 payload 쪽과 같다.
        String wrongDimension =
                document(DIMENSION, DIMENSION, 0).replace("\"dimension\":" + DIMENSION, "\"dimension\":512");

        for (var invalid : List.of(
                embeddingResult(wrongSchema, DIMENSION, 1, List.of(1), true),
                embeddingResult(wrongDimension, DIMENSION, 1, List.of(1), true)))
            assertThatThrownBy(() -> adapter.validateAndStore(
                            703, 702, "text_embedding", "runs/703/text_embedding/a1/", invalid))
                    .isInstanceOf(BusinessException.class);
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void rejectsAnEmbeddingWithoutTheTextItWasMadeFrom() throws Exception {
        storeTwoScenes();

        // FRD §7.2 — "그때 무엇을 임베딩했나" 를 나중에 물을 수 있어야 한다.
        String blank = document(DIMENSION, DIMENSION, 0).replace("\"sourceText\":\"광안대교\"", "\"sourceText\":\" \"");

        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "text_embedding",
                        "runs/703/text_embedding/a1/",
                        embeddingResult(blank, DIMENSION, 1, List.of(1), true)))
                .isInstanceOf(BusinessException.class);
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void rejectsAResultThatLeavesSomeSceneNeitherEmbeddedNorSkipped() throws Exception {
        storeTwoScenes();

        // 장면 2개인데 1개만 말했다. 워커가 장면을 흘린 것을 잡는 유일한 불변식이다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "text_embedding",
                        "runs/703/text_embedding/a1/",
                        embeddingResult(document(DIMENSION, DIMENSION, 0), 1, List.of(), true)))
                .isInstanceOf(BusinessException.class);
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void indexingRejectsAnEmbeddedCountThatDisagreesWithTheStoredVectors() throws Exception {
        storeTwoScenes();
        adapter.validateAndStore(
                703,
                702,
                "text_embedding",
                "runs/703/text_embedding/a1/",
                embeddingResult(document(DIMENSION, DIMENSION, 0), 1, List.of(1), true));

        var miscounted = new java.util.LinkedHashMap<>(summary(2));
        miscounted.put("embeddedScenes", 0);
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703, 702, "indexing", "runs/703/indexing/a1/", body("indexing", miscounted)))
                .isInstanceOf(BusinessException.class);

        var correct = new java.util.LinkedHashMap<>(summary(2));
        correct.put("embeddedScenes", 1);
        assertThat(adapter.validateAndStore(703, 702, "indexing", "runs/703/indexing/a1/", body("indexing", correct)))
                .isEmpty();
    }
}
