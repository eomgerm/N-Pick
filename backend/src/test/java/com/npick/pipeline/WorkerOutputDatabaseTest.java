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
                        "entity_extraction",
                        "runs/703/entity_extraction/a1/",
                        body("entity_extraction", Map.of("entities", List.of()))))
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

    /** S15P21A501-175 확정 모델. 리비전은 dense 리더가 요구하는 40자리 hex 다. */
    private static final String PINNED_MODEL = "dragonkue/snowflake-arctic-embed-l-v2.0-ko@" + "a".repeat(40);

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
        result.put("versions", versions(PINNED_MODEL));
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
    void supportsTheWiredStagesAndNoOthers() {
        // `supports()` 가 거짓이면 WorkerExecutionBinding 이 claim capabilities 에서 지운다.
        assertThat(adapter.supports("text_embedding")).isTrue();
        assertThat(adapter.supports("indexing")).isTrue();
        assertThat(adapter.supports("scene_transcript_mapping")).isTrue();
        assertThat(adapter.supports("entity_extraction")).isFalse();
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

    private static Map<String, Object> versions(String modelVersion) {
        return Map.of(
                "outputSchemaVersion",
                "npick.stage.text_embedding.output/v1",
                "detail",
                Map.of("modelVersion", modelVersion));
    }

    @Test
    void rejectsAModelVersionWhoseRevisionIsNotPinned() throws Exception {
        storeTwoScenes();
        var result = embeddingResult(document(DIMENSION, DIMENSION, 0), 1, List.of(1), true);

        // 움직이는 ref 로 만든 벡터는 저장돼도 dense 리더가 `missing_model` 로 전량
        // 제외한다(`DenseSceneCandidateAdapter`). 여기서 막지 않으면 정본·요약·채널 상태가
        // 전부 정상이라고 말하는데 dense 채널만 조용히 죽는다.
        result.put("versions", versions("dragonkue/snowflake-arctic-embed-l-v2.0-ko@main"));

        assertThatThrownBy(() ->
                        adapter.validateAndStore(703, 702, "text_embedding", "runs/703/text_embedding/a1/", result))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("JOB_400_001"));
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void rejectsAnEmbeddingResultThatDoesNotSayWhichWeightsMadeIt() throws Exception {
        storeTwoScenes();
        var withoutDetail = embeddingResult(document(DIMENSION, DIMENSION, 0), 1, List.of(1), true);
        withoutDetail.put("versions", Map.of("outputSchemaVersion", "npick.stage.text_embedding.output/v1"));
        var withSpace = embeddingResult(document(DIMENSION, DIMENSION, 0), 1, List.of(1), true);
        withSpace.put("versions", versions("두 낱말@" + "a".repeat(40)));

        for (var invalid : List.of(withoutDetail, withSpace))
            assertThatThrownBy(() -> adapter.validateAndStore(
                            703, 702, "text_embedding", "runs/703/text_embedding/a1/", invalid))
                    .isInstanceOf(BusinessException.class);
        assertThat(embeddedScenes()).isZero();
    }

    @Test
    void attributesABrokenEmbeddingColumnToTheSchemaNotTheWorker() throws Exception {
        storeTwoScenes();
        // 차원 없는 `vector` 로 바꾸면 atttypmod 가 -1 이다. @DataJpaTest 가 롤백한다.
        jdbc.execute("ALTER TABLE npick.scene ALTER COLUMN embedding TYPE vector");

        // 워커 과실이 아니다. `invalid()` 는 JOB_400_001(영구)이라 워커가 자기 출력을
        // 의심하며 단계를 실패로 닫는데, 고쳐야 하는 것은 BE 스키마다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "text_embedding",
                        "runs/703/text_embedding/a1/",
                        embeddingResult(document(DIMENSION, DIMENSION, 0), 1, List.of(1), true)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    // ── scene_transcript_mapping (S15P21A501-191) ──────────────────────

    private static final String MAPPING_PREFIX = "runs/703/scene_transcript_mapping/a1/";

    private static final String MAPPING_SCHEMA = "npick.stage.scene_transcript_mapping.output/v1";

    /** S15P21A501-98 이 싣는 축. `ocr`·`vlm_metadata` 와 같은 모양이다. */
    private static final String TOKENIZER = "query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0";

    private static Map<String, Object> segment(String id, int s, int e, String text, String source) {
        return Map.<String, Object>of("segmentId", id, "s", s, "e", e, "t", text, "sourceDetail", source);
    }

    private static Map<String, Object> link(String segmentId, int overlapMs) {
        return Map.<String, Object>of("segmentId", segmentId, "overlapMs", overlapMs);
    }

    private static Map<String, Object> mapped(int index, String tokens, List<Map<String, Object>> links) {
        return Map.<String, Object>of("sceneIndex", index, "segments", links, "tokens", tokens);
    }

    /** 로컬 저장소에 올리고 등록용 ArtifactRef 를 돌려준다. 본문 해시로 키를 갈라 한 테스트가 여러 변형을 올린다. */
    private Map<String, Object> upload(String kind, String name, String json) throws Exception {
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String key = MAPPING_PREFIX + name + "-" + hash.substring(0, 8) + ".json";
        try (var upload = new LocalWorkerArtifactAdapter(root)
                .prepareUpload(MAPPING_PREFIX, key, bytes.length, hash, new java.io.ByteArrayInputStream(bytes))) {
            upload.publish();
        }
        return Map.<String, Object>of("kind", kind, "storageKey", key, "byteSize", bytes.length, "contentHash", hash);
    }

    /**
     * 두 snapshot 을 올리고 매핑 단계의 complete payload 를 만든다.
     *
     * <p>{@code adopted} 에 없는 구간은 {@code selected=false} 인 보관 전용이다. 채택 정책 자체는 상류 소유라 사유 코드는 모양만 맞춘다.
     */
    private Map<String, Object> mappingResult(
            List<Map<String, Object>> segments, List<String> adopted, List<Map<String, Object>> scenes)
            throws Exception {
        return mappingResult(
                segmentsDocument(segments), segmentsRef -> decisionsDocument(segments, adopted, segmentsRef), scenes);
    }

    private static Map<String, Object> segmentsDocument(List<Map<String, Object>> segments) {
        return Map.of("schemaVersion", "npick.transcript.segments/v1", "segments", segments);
    }

    private static Map<String, Object> decision(String id, boolean selected, String reason, List<String> conflicts) {
        return Map.<String, Object>of(
                "segmentId", id, "selected", selected, "reasonCode", reason, "conflictsWith", conflicts);
    }

    private static final List<String> SOURCES = List.of("uploaded", "embedded", "asr");

    /** 제외 근거는 같은 snapshot 에서 <b>채택된 상위 출처</b>여야 하고 실제로 시간이 겹쳐야 한다(계약 §4.5). */
    private static String higherPriorityOverlap(
            List<Map<String, Object>> segments, List<String> adopted, Map<String, Object> excluded) {
        for (var other : segments)
            if (adopted.contains(other.get("segmentId"))
                    && SOURCES.indexOf(other.get("sourceDetail")) < SOURCES.indexOf(excluded.get("sourceDetail"))
                    && Math.max((int) other.get("s"), (int) excluded.get("s"))
                            < Math.min((int) other.get("e"), (int) excluded.get("e")))
                return (String) other.get("segmentId");
        // 조용히 넘기면 거절 사유가 테스트 이름과 어긋난 채로 초록이 된다.
        throw new IllegalArgumentException("fixture has no valid conflict for " + excluded.get("segmentId"));
    }

    private static Map<String, Object> decisionsDocument(
            List<Map<String, Object>> segments, List<String> adopted, Map<String, Object> segmentsRef) {
        var decisions = new java.util.ArrayList<Map<String, Object>>();
        for (var segment : segments) {
            String id = (String) segment.get("segmentId");
            decisions.add(
                    adopted.contains(id)
                            ? decision(
                                    id,
                                    true,
                                    "asr".equals(segment.get("sourceDetail")) ? "ASR_SUPPLEMENT" : "PREFERRED_SUBTITLE",
                                    List.of())
                            : decision(
                                    id,
                                    false,
                                    "OVERLAPS_HIGHER_PRIORITY",
                                    List.of(higherPriorityOverlap(segments, adopted, segment))));
        }
        return decisionsDocument(segmentsRef, decisions);
    }

    private static Map<String, Object> decisionsDocument(
            Map<String, Object> segmentsRef, List<Map<String, Object>> decisions) {
        return Map.of(
                "schemaVersion",
                "npick.transcript.decisions/v1",
                "segmentsArtifact",
                segmentsRef,
                "decisions",
                decisions);
    }

    /** 판정 목록을 직접 주입한다. 계약을 어기는 판정을 만드는 테스트가 쓴다. */
    private Map<String, Object> mappingWithDecisions(
            List<Map<String, Object>> segments, List<Map<String, Object>> decisions, List<Map<String, Object>> scenes)
            throws Exception {
        return mappingResult(segmentsDocument(segments), ref -> decisionsDocument(ref, decisions), scenes);
    }

    /** {@code decisions} 는 segments 참조를 받아 만든다 — 올려 봐야 그 참조를 알 수 있다. */
    private Map<String, Object> mappingResult(
            Map<String, Object> segmentsDocument,
            java.util.function.Function<Map<String, Object>, Map<String, Object>> decisionsDocument,
            List<Map<String, Object>> scenes)
            throws Exception {
        var mapper = new JsonMapper();
        var segmentsRef = upload("transcript_segments", "segments", mapper.writeValueAsString(segmentsDocument));
        var decisionsRef = upload(
                "transcript_decisions", "decisions", mapper.writeValueAsString(decisionsDocument.apply(segmentsRef)));
        var result = new java.util.LinkedHashMap<>(body(
                "scene_transcript_mapping",
                Map.of(
                        "transcript",
                        Map.of("segmentsArtifact", segmentsRef, "decisionsArtifact", decisionsRef),
                        "scenes",
                        scenes)));
        result.put("artifacts", List.of(segmentsRef, decisionsRef));
        // 색인과 질의가 같은 Kiwi 설정을 썼는지 나중에 되짚을 수 있는 유일한 기록이다(계약 §4.5).
        result.put("versions", Map.of("outputSchemaVersion", MAPPING_SCHEMA, "detail", Map.of("tokenizer", TOKENIZER)));
        return result;
    }

    private Map<String, Object> storeMapping(Map<String, Object> result) {
        return adapter.validateAndStore(703, 702, "scene_transcript_mapping", MAPPING_PREFIX, result);
    }

    /** {@code sceneIndex} 순서로 읽는다 — 어댑터가 장면을 가리키는 것과 같은 규약이다. */
    private Map<String, Object> sceneRow(int index) {
        return jdbc.queryForList("""
                        SELECT transcript_text, transcript_tokens, transcript_json, transcript_source
                        FROM npick.scene WHERE pipeline_run_id=703 ORDER BY start_time_ms, scene_id
                        """).get(index);
    }

    @Test
    void storesDialogueTextTokensAndJsonForEachLinkedScene() throws Exception {
        storeTwoScenes();
        var segments = List.of(segment("a", 0, 600, "첫 대사", "uploaded"), segment("b", 600, 900, "둘째 대사", "uploaded"));

        storeMapping(mappingResult(
                segments,
                List.of("a", "b"),
                List.of(mapped(0, "첫 대사 둘째", List.of(link("a", 600), link("b", 300))), mapped(1, "", List.of()))));

        var linked = sceneRow(0);
        assertThat(linked.get("transcript_text")).isEqualTo("첫 대사 둘째 대사");
        // 워커가 만든 토큰을 그대로 둔다. BE 에 Kiwi 가 없어 다시 만들 수도 없고,
        // 색인과 질의가 다른 설정을 쓰면 검색이 0건이 된다.
        assertThat(linked.get("transcript_tokens")).isEqualTo("첫 대사 둘째");
        assertThat(linked.get("transcript_source")).isEqualTo("provided");
        var json = new JsonMapper().readTree(String.valueOf(linked.get("transcript_json")));
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.get(0).get("s").asInt()).isEqualTo(0);
        assertThat(json.get(0).get("e").asInt()).isEqualTo(600);
        assertThat(json.get(0).get("t").asString()).isEqualTo("첫 대사");
        assertThat(json.get(0).get("overlap_ms").asInt()).isEqualTo(600);
        assertThat(json.get(1).get("overlap_ms").asInt()).isEqualTo(300);
        // 대사 없는 장면은 칸을 비운 채 둔다. 빈 문자열과 없음은 게시 판정에서 갈린다.
        assertThat(sceneRow(1)).allSatisfy((column, value) -> assertThat(value).isNull());
    }

    @Test
    void joinsAdoptedTextWithASingleSpaceInLinkOrder() throws Exception {
        storeTwoScenes();
        var segments = List.of(
                segment("a", 0, 300, "하나", "uploaded"),
                segment("b", 300, 600, "둘", "uploaded"),
                segment("c", 600, 900, "셋", "uploaded"));

        storeMapping(mappingResult(
                segments,
                List.of("a", "b", "c"),
                List.of(
                        mapped(0, "하나 둘 셋", List.of(link("a", 300), link("b", 300), link("c", 300))),
                        mapped(1, "", List.of()))));

        // 워커가 토큰을 만들 때 이은 것과 같은 구분자·같은 순서다. 두 칸이 같은 문장을 가리켜야 한다.
        assertThat(sceneRow(0).get("transcript_text")).isEqualTo("하나 둘 셋");
    }

    @Test
    void derivesProvidedWhenAnyAdoptedSegmentIsNotAsr() throws Exception {
        storeTwoScenes();
        var cases = Map.of("uploaded", "provided", "embedded", "provided", "asr", "asr");

        for (var expected : cases.entrySet()) {
            storeMapping(mappingResult(
                    List.of(segment("a", 0, 600, "대사", expected.getKey())),
                    List.of("a"),
                    List.of(mapped(0, "대사", List.of(link("a", 600))), mapped(1, "", List.of()))));
            assertThat(sceneRow(0).get("transcript_source")).isEqualTo(expected.getValue());
        }

        // 자막 공백을 ASR 이 채운 장면이다. 영상 단위 출처와 장면 출처가 갈리는 자리(FRD F-03).
        storeMapping(mappingResult(
                List.of(segment("a", 0, 300, "자막", "uploaded"), segment("b", 300, 600, "발화", "asr")),
                List.of("a", "b"),
                List.of(mapped(0, "자막 발화", List.of(link("a", 300), link("b", 300))), mapped(1, "", List.of()))));
        assertThat(sceneRow(0).get("transcript_source")).isEqualTo("provided");
    }

    private void assertRejects(Map<String, Object> result) {
        assertThatThrownBy(() -> storeMapping(result))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("JOB_400_001"));
    }

    @Test
    void rejectsLinksToSegmentsThatWereNotAdopted() throws Exception {
        storeTwoScenes();
        var segments = List.of(segment("keep", 0, 600, "채택", "uploaded"), segment("drop", 0, 600, "보관", "embedded"));

        // 보관 전용 구간의 원문이 새면 채택하지 않은 자막으로 장면이 검색된다.
        assertRejects(mappingResult(
                segments,
                List.of("keep"),
                List.of(mapped(0, "보관", List.of(link("drop", 600))), mapped(1, "", List.of()))));
        // 어느 snapshot 에도 없는 ID 다. 원문을 찾을 수 없으므로 빈 대사로 넘기지 않는다.
        assertRejects(mappingResult(
                segments,
                List.of("keep"),
                List.of(mapped(0, "유령", List.of(link("ghost", 600))), mapped(1, "", List.of()))));
        assertThat(sceneRow(0).get("transcript_text")).isNull();
    }

    @Test
    void rejectsSceneIndexesThatAreNotThisRunsScenes() throws Exception {
        storeTwoScenes();
        var segments = List.of(segment("a", 0, 600, "대사", "uploaded"));
        var linked = mapped(0, "대사", List.of(link("a", 600)));
        var empty = mapped(1, "", List.of());

        for (var scenes : List.of(
                // 이 회차에 없는 장면. 다른 회차의 장면을 건드리지 않는다.
                List.of(linked, empty, mapped(2, "", List.of())),
                // 같은 장면이 두 번. 뒤 연결이 앞 연결을 조용히 덮는다.
                List.of(linked, mapped(0, "대사", List.of(link("a", 600)))),
                // 장면 하나가 빠졌다. 빠진 장면은 "대사 없음" 으로 굳는다.
                List.of(linked))) assertRejects(mappingResult(segments, List.of("a"), scenes));
        assertThat(sceneRow(0).get("transcript_text")).isNull();
    }

    @Test
    void rejectsAnOverlapLongerThanTheSegment() throws Exception {
        storeTwoScenes();

        assertRejects(mappingResult(
                List.of(segment("a", 0, 600, "대사", "uploaded")),
                List.of("a"),
                List.of(mapped(0, "대사", List.of(link("a", 700))), mapped(1, "", List.of()))));
    }

    @Test
    void rejectsLinksThatAreNotInTimeOrder() throws Exception {
        storeTwoScenes();
        var segments = List.of(segment("a", 0, 300, "먼저", "uploaded"), segment("b", 300, 600, "나중", "uploaded"));

        // 워커는 연결 순서대로 이어 토큰을 만든다. 순서가 시간순이 아니면 BE 가 만드는
        // `transcript_text` 와 그 토큰이 다른 문장을 가리킨다.
        assertRejects(mappingResult(
                segments,
                List.of("a", "b"),
                List.of(mapped(0, "먼저 나중", List.of(link("b", 300), link("a", 300))), mapped(1, "", List.of()))));
    }

    @Test
    void rejectsTheSameSegmentLinkedTwiceToOneScene() throws Exception {
        storeTwoScenes();

        // 같은 구간이 두 번 들어오면 원문이 두 번 이어져 워커가 한 번만 센 토큰과 갈린다.
        assertRejects(mappingResult(
                List.of(segment("a", 0, 600, "대사", "uploaded")),
                List.of("a"),
                List.of(mapped(0, "대사", List.of(link("a", 600), link("a", 600))), mapped(1, "", List.of()))));
    }

    @Test
    void requiresTokensToBePresentEvenWhenEmpty() throws Exception {
        storeTwoScenes();
        var segments = List.of(segment("a", 0, 600, "어", "uploaded"));

        assertRejects(mappingResult(
                segments,
                List.of("a"),
                List.of(
                        Map.<String, Object>of("sceneIndex", 0, "segments", List.of(link("a", 600))),
                        mapped(1, "", List.of()))));
        // 내용어가 없는 대사는 원문이 있어도 토큰이 빈 문자열이다. 키 생략과 구분한다.
        storeMapping(mappingResult(
                segments, List.of("a"), List.of(mapped(0, "", List.of(link("a", 600))), mapped(1, "", List.of()))));
        assertThat(sceneRow(0).get("transcript_tokens")).isEqualTo("");
        assertThat(sceneRow(0).get("transcript_text")).isEqualTo("어");
    }

    @Test
    void doesNotPartiallyStoreWhenALaterSceneIsInvalid() throws Exception {
        storeTwoScenes();
        var segments =
                List.of(segment("first", 0, 600, "앞", "uploaded"), segment("second", 1000, 1600, "뒤", "uploaded"));

        // 뒤 장면이 신고한 겹침이 실제와 다르다 — 장면 1 은 1000~2000 이라 600 이어야 한다.
        // 앞 장면은 멀쩡하므로 거절 사유가 이 테스트의 이름과 정확히 맞는다.
        assertRejects(mappingResult(
                segments,
                List.of("first", "second"),
                List.of(mapped(0, "앞", List.of(link("first", 600))), mapped(1, "뒤", List.of(link("second", 500))))));

        // 앞 장면만 남으면 거절된 run 에 반쪽 대사가 굳는다. 검증을 모두 마친 뒤에 쓴다.
        assertThat(sceneRow(0)).allSatisfy((column, value) -> assertThat(value).isNull());
    }

    @Test
    void rejectsDecisionsThatDoNotBelongToTheSegmentsSnapshot() throws Exception {
        storeTwoScenes();
        var segments = List.of(segment("a", 0, 600, "대사", "uploaded"));
        var scenes = List.of(mapped(0, "대사", List.of(link("a", 600))), mapped(1, "", List.of()));
        // 구간 ID 는 snapshot 사이에 보존된다. 그래서 4단계의 예비 판정 파일을 가리켜도 모든
        // ID 가 해석되고 아무것도 실패하지 않는다 — 예비 판정이 최종으로 되살아난다(계약 §4.5).
        var stale = upload(
                "transcript_segments",
                "stale-segments",
                new JsonMapper().writeValueAsString(segmentsDocument(segments)) + " ");

        assertRejects(mappingResult(
                segmentsDocument(segments), ignored -> decisionsDocument(segments, List.of("a"), stale), scenes));
    }

    @Test
    void rejectsSnapshotsWithTheWrongSchemaVersion() throws Exception {
        storeTwoScenes();
        var segments = List.of(segment("a", 0, 600, "대사", "uploaded"));
        var scenes = List.of(mapped(0, "대사", List.of(link("a", 600))), mapped(1, "", List.of()));

        assertRejects(mappingResult(
                Map.of("schemaVersion", "npick.transcript.segments/v2", "segments", segments),
                ref -> decisionsDocument(segments, List.of("a"), ref),
                scenes));
        assertRejects(mappingResult(
                segmentsDocument(segments),
                ref -> Map.of(
                        "schemaVersion",
                        "npick.transcript.decisions/v2",
                        "segmentsArtifact",
                        ref,
                        "decisions",
                        List.of(Map.of(
                                "segmentId",
                                "a",
                                "selected",
                                true,
                                "reasonCode",
                                "PREFERRED_SUBTITLE",
                                "conflictsWith",
                                List.of()))),
                scenes));
    }

    @Test
    void rejectsDecisionsThatAreDuplicatedOrIncomplete() throws Exception {
        storeTwoScenes();
        var segments = List.of(segment("a", 0, 600, "대사", "uploaded"), segment("b", 600, 900, "둘째", "uploaded"));
        var scenes = List.of(mapped(0, "대사 둘째", List.of(link("a", 600), link("b", 300))), mapped(1, "", List.of()));
        var a = decision("a", true, "PREFERRED_SUBTITLE", List.of());

        for (var broken : List.of(
                // 같은 구간에 판정이 둘. `selected=true` 쪽이 조용히 이긴다.
                List.of(a, a, decision("b", true, "PREFERRED_SUBTITLE", List.of())),
                // `b` 의 판정이 없다. 상류가 채택을 주장한 적 없는데 미채택으로 조용히 흐른다.
                List.of(a))) assertRejects(mappingWithDecisions(segments, broken, scenes));
    }

    @Test
    void rejectsDecisionsWithAnImpossibleReasonOrConflict() throws Exception {
        storeTwoScenes();
        var subtitle = segment("sub", 0, 600, "자막", "uploaded");
        var speech = segment("voice", 0, 600, "발화", "asr");
        var far = segment("far", 1200, 1800, "먼 자막", "embedded");
        var bySubtitle = List.of(mapped(0, "자막", List.of(link("sub", 600))), mapped(1, "", List.of()));
        var bySpeech = List.of(mapped(0, "발화", List.of(link("voice", 600))), mapped(1, "", List.of()));
        var adoptedSubtitle = decision("sub", true, "PREFERRED_SUBTITLE", List.of());

        // 채택된 ASR 구간의 사유는 `ASR_SUPPLEMENT` 다. `ProcessingRecordReader.selectedSources` 가
        // 같은 규칙으로 이 snapshot 을 읽으므로, 어긋나면 대사는 저장되는데 처리 상세는
        // "자막 없음" 으로 뜬다 — 저장·검색 상태와 화면이 갈린다.
        assertRejects(mappingWithDecisions(
                List.of(speech), List.of(decision("voice", true, "PREFERRED_SUBTITLE", List.of())), bySpeech));
        // 제외 근거가 이 snapshot 에 없는 ID 다.
        assertRejects(mappingWithDecisions(
                List.of(subtitle, speech),
                List.of(adoptedSubtitle, decision("voice", false, "OVERLAPS_HIGHER_PRIORITY", List.of("ghost"))),
                bySubtitle));
        // 채택된 구간에는 제외 근거가 없어야 하고, 제외된 구간에는 있어야 한다.
        assertRejects(mappingWithDecisions(
                List.of(subtitle, speech),
                List.of(
                        decision("sub", true, "PREFERRED_SUBTITLE", List.of("voice")),
                        decision("voice", false, "OVERLAPS_HIGHER_PRIORITY", List.of("sub"))),
                bySubtitle));
        assertRejects(mappingWithDecisions(
                List.of(subtitle, speech),
                List.of(adoptedSubtitle, decision("voice", false, "OVERLAPS_HIGHER_PRIORITY", List.of())),
                bySubtitle));
        // 근거가 하위 출처다 — ASR 이 제공 자막을 밀어낼 수 없다.
        assertRejects(mappingWithDecisions(
                List.of(subtitle, speech),
                List.of(
                        decision("voice", true, "ASR_SUPPLEMENT", List.of()),
                        decision("sub", false, "OVERLAPS_HIGHER_PRIORITY", List.of("voice"))),
                bySpeech));
        // 근거와 시간이 겹치지 않는다. 겹치지 않는 구간은 서로를 밀어낼 수 없다.
        assertRejects(mappingWithDecisions(
                List.of(subtitle, far),
                List.of(adoptedSubtitle, decision("far", false, "OVERLAPS_HIGHER_PRIORITY", List.of("sub"))),
                bySubtitle));
    }

    @Test
    void rejectsAMissingTokenizerIdentity() throws Exception {
        storeTwoScenes();
        var result = new java.util.LinkedHashMap<>(mappingResult(
                List.of(segment("a", 0, 600, "대사", "uploaded")),
                List.of("a"),
                List.of(mapped(0, "대사", List.of(link("a", 600))), mapped(1, "", List.of()))));

        // 색인과 질의의 Kiwi 설정이 어긋나면 검색이 오류 없이 0건이 된다. 그때 어느 설정이
        // 이 토큰을 만들었는지 되짚을 근거가 이 축 하나뿐이다(계약 §4.5).
        for (Map<String, Object> versions : List.of(
                Map.<String, Object>of("outputSchemaVersion", MAPPING_SCHEMA),
                Map.<String, Object>of("outputSchemaVersion", MAPPING_SCHEMA, "detail", Map.of()),
                Map.<String, Object>of("outputSchemaVersion", MAPPING_SCHEMA, "detail", Map.of("tokenizer", " ")))) {
            result.put("versions", versions);
            assertRejects(Map.copyOf(result));
        }
    }

    @Test
    void rejectsAnOverlapThatIsNotTheActualSceneOverlap() throws Exception {
        storeTwoScenes();

        // 장면 0 은 0~1000 이다. 이 구간은 장면과 전혀 겹치지 않는데 연결됐다.
        assertRejects(mappingResult(
                List.of(segment("a", 1200, 1800, "다른 장면", "uploaded")),
                List.of("a"),
                List.of(mapped(0, "다른 장면", List.of(link("a", 600))), mapped(1, "", List.of()))));
        // 겹치기는 하지만 신고한 시간이 실제와 다르다. `transcript_json.overlap_ms` 의 정의가
        // "이 장면과 겹친 시간" 이라 지어낸 값이 정본에 남는다.
        assertRejects(mappingResult(
                List.of(segment("a", 0, 600, "대사", "uploaded")),
                List.of("a"),
                List.of(mapped(0, "대사", List.of(link("a", 500))), mapped(1, "", List.of()))));
    }

    @Test
    void rejectsANulCharacterInsteadOfBreakingTheTransaction() throws Exception {
        storeTwoScenes();

        // PostgreSQL 의 `text`·`jsonb` 가 NUL 을 받지 못한다. 여기서 막지 않으면 드라이버 예외가
        // BusinessException 을 우회해 500 이 되고, 그 응답은 재시도 가능으로 분류돼 재배정을 반복한다.
        assertRejects(mappingResult(
                List.of(segment("a", 0, 600, "대 사", "uploaded")),
                List.of("a"),
                List.of(mapped(0, "대사", List.of(link("a", 600))), mapped(1, "", List.of()))));
        assertRejects(mappingResult(
                List.of(segment("a", 0, 600, "대사", "uploaded")),
                List.of("a"),
                List.of(mapped(0, "대 사", List.of(link("a", 600))), mapped(1, "", List.of()))));
    }

    @Test
    void rejectsTokensOnASceneWithNoLinks() throws Exception {
        storeTwoScenes();

        // 연결이 없는데 토큰이 있다는 것은 스스로 모순인 출력이다. 조용히 버리면 워커의
        // 결함이 "대사 없는 장면" 으로 위장된다.
        assertRejects(mappingResult(
                List.of(segment("a", 0, 600, "대사", "uploaded")),
                List.of("a"),
                List.of(mapped(0, "대사", List.of(link("a", 600))), mapped(1, "있음", List.of()))));
    }

    @Test
    void rejectsAnOverlapThatIsZeroNegativeOrNotAnInteger() throws Exception {
        storeTwoScenes();
        var segments = List.of(segment("a", 0, 600, "대사", "uploaded"));

        for (Object overlap : List.of(0, -600, 600.5, "600"))
            assertRejects(mappingResult(
                    segments,
                    List.of("a"),
                    List.of(
                            mapped(0, "대사", List.of(Map.<String, Object>of("segmentId", "a", "overlapMs", overlap))),
                            mapped(1, "", List.of()))));
        // 키 자체가 없는 경우도 같다.
        assertRejects(mappingResult(
                segments,
                List.of("a"),
                List.of(mapped(0, "대사", List.of(Map.<String, Object>of("segmentId", "a"))), mapped(1, "", List.of()))));
    }

    @Test
    void rejectsAMalformedMappingEnvelope() throws Exception {
        storeTwoScenes();
        var segments = List.of(segment("a", 0, 600, "대사", "uploaded"));
        var scenes = List.of(mapped(0, "대사", List.of(link("a", 600))), mapped(1, "", List.of()));

        // `transcript` 가 통째로 없다. 두 snapshot 없이는 원문을 찾을 수 없다.
        assertRejects(body("scene_transcript_mapping", Map.of("scenes", scenes)));
        // 한 장면의 `segments` 가 배열이 아니다.
        assertRejects(mappingResult(
                segments,
                List.of("a"),
                List.of(
                        Map.<String, Object>of("sceneIndex", 0, "segments", "없음", "tokens", "대사"),
                        mapped(1, "", List.of()))));
        // 참조가 `artifacts` 에 등록되지 않았다 — 올린 적 없는 파일을 정본으로 읽지 않는다.
        var unregistered = new java.util.LinkedHashMap<>(mappingResult(segments, List.of("a"), scenes));
        unregistered.put("artifacts", List.of());
        assertRejects(unregistered);
    }
}
