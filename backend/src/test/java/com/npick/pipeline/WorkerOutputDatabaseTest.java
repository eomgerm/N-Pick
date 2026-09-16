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
import com.npick.pipeline.domain.model.PipelineStages;
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
                // 단계마다 다르다 — `ocr`·`vlm_metadata` 는 v2 다. 문자열을 여기서 다시 조립하면
                // 그 둘의 봉투 검사를 테스트가 통과시켜 버린다.
                Map.of("outputSchemaVersion", PipelineStages.outputSchema(stage)),
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

    // ── ocr · vlm_metadata (S15P21A501-184) ─────────────────────────────

    private static final JsonMapper MAPPER = new JsonMapper();

    private static final String FRAMES = "runs/703/frame_extraction/a1/";
    private static final String KEY_0 = FRAMES + "s0000/kf-000000500.jpg";
    private static final String KEY_1 = FRAMES + "s0001/kf-000001500.jpg";

    /** 장면 2개와 각 장면의 keyframe 1장. `ocr`·`vlm_metadata` 가 붙을 자리다. */
    private void storeKeyframes() throws Exception {
        storeTwoScenes();
        byte[] image = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        String hash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(image));
        var store = new LocalWorkerArtifactAdapter(root);
        List<Map<String, Object>> refs = new java.util.ArrayList<>();
        for (String key : List.of(KEY_0, KEY_1)) {
            try (var upload =
                    store.prepareUpload(FRAMES, key, image.length, hash, new java.io.ByteArrayInputStream(image))) {
                upload.publish();
            }
            refs.add(Map.of("kind", "keyframe", "storageKey", key, "byteSize", image.length, "contentHash", hash));
        }
        var result = new java.util.LinkedHashMap<>(body(
                "frame_extraction",
                Map.of(
                        "imageWidth",
                        1920,
                        "imageHeight",
                        1080,
                        "scenes",
                        List.of(
                                Map.of(
                                        "sceneIndex",
                                        0,
                                        "representativeTimestampMs",
                                        500,
                                        "keyframes",
                                        List.of(Map.of("sceneIndex", 0, "timestampMs", 500, "storageKey", KEY_0))),
                                Map.of(
                                        "sceneIndex",
                                        1,
                                        "representativeTimestampMs",
                                        1500,
                                        "keyframes",
                                        List.of(Map.of("sceneIndex", 1, "timestampMs", 1500, "storageKey", KEY_1)))))));
        result.put("artifacts", refs);
        adapter.validateAndStore(703, 702, "frame_extraction", FRAMES, result);
    }

    private static Map<String, Object> observation(
            int sceneIndex, int timestampMs, String storageKey, String rawText, String tokens, double confidence) {
        return Map.of(
                "sceneIndex",
                sceneIndex,
                "timestampMs",
                timestampMs,
                "storageKey",
                storageKey,
                "rawText",
                rawText,
                "tokens",
                tokens,
                "confidence",
                confidence,
                "unverified",
                false,
                "textKey",
                "3f1ea70c9b21",
                "boundingBox",
                Map.of(
                        "points",
                        List.of(List.of(262, 96), List.of(531, 96), List.of(531, 176), List.of(262, 176)),
                        "x",
                        262,
                        "y",
                        96,
                        "width",
                        269,
                        "height",
                        80));
    }

    /** `ocr` 의 complete 본문. v2 는 같은 출력을 {@code ocr_result} 산출물로도 보존한다(계약 §4.3.2). */
    private Map<String, Object> ocrResult(List<Map<String, Object>> observations, int keyframesRead, boolean register)
            throws Exception {
        return ocrResult(observations, keyframesRead, register, output -> output);
    }

    private Map<String, Object> ocrResult(
            List<Map<String, Object>> observations,
            int keyframesRead,
            boolean register,
            java.util.function.UnaryOperator<Map<String, Object>> storedOutput)
            throws Exception {
        return ocrResult(
                observations, keyframesRead, register, storedOutput, java.util.function.UnaryOperator.identity(), true);
    }

    private Map<String, Object> ocrResult(
            List<Map<String, Object>> observations,
            int keyframesRead,
            boolean register,
            java.util.function.UnaryOperator<Map<String, Object>> storedOutput,
            java.util.function.UnaryOperator<Map<String, Object>> sentOutput)
            throws Exception {
        return ocrResult(observations, keyframesRead, register, storedOutput, sentOutput, true);
    }

    /** {@code withIdentity} 가 거짓이면 산출물 파일에서 재현 튜플을 뺀다. */
    private Map<String, Object> ocrResult(
            List<Map<String, Object>> observations,
            int keyframesRead,
            boolean register,
            java.util.function.UnaryOperator<Map<String, Object>> storedOutput,
            java.util.function.UnaryOperator<Map<String, Object>> sentOutput,
            boolean withIdentity)
            throws Exception {
        List<Map<String, Object>> groups = new java.util.ArrayList<>();
        for (int index = 0; index < observations.size(); index++)
            groups.add(Map.of(
                    "sceneIndex",
                    observations.get(index).get("sceneIndex"),
                    "observationIndices",
                    List.of(index),
                    "representativeIndex",
                    index));
        Map<String, Object> output = Map.of(
                "observations",
                observations,
                "textGroups",
                groups,
                "mergeConfigVersion",
                "ocr-merge/v1:28d42216",
                "keyframesRead",
                keyframesRead,
                "minConfidence",
                0.7);
        String prefix = "runs/703/ocr/a1/";
        var document = new java.util.LinkedHashMap<String, Object>();
        document.put("outputSchemaVersion", PipelineStages.outputSchema("ocr"));
        if (withIdentity) document.put("identity", Map.of("stageVersion", "npick.stage.ocr/v1:bc75979d"));
        document.put("output", storedOutput.apply(output));
        byte[] bytes = MAPPER.writeValueAsString(document).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String key = prefix + "ocr-result-" + hash.substring(0, 8) + ".json";
        try (var upload = new LocalWorkerArtifactAdapter(root)
                .prepareUpload(prefix, key, bytes.length, hash, new java.io.ByteArrayInputStream(bytes))) {
            upload.publish();
        }
        var ref = Map.<String, Object>of(
                "kind", "ocr_result", "storageKey", key, "byteSize", bytes.length, "contentHash", hash);
        var result = new java.util.LinkedHashMap<>(body("ocr", sentOutput.apply(output)));
        result.put("artifacts", register ? List.of(ref) : List.of());
        return result;
    }

    private List<Map<String, Object>> storedObservations() {
        return jdbc.queryForList("""
                SELECT o.raw_text, o.tokens, o.confidence, o.bounding_box_json::text AS box, k.storage_key
                FROM npick.ocr_observation o JOIN npick.keyframe k USING (keyframe_id)
                ORDER BY o.ocr_observation_id
                """);
    }

    @Test
    void storesObservationsAgainstTheKeyframeTheyWereReadFrom() throws Exception {
        storeKeyframes();

        assertThat(adapter.validateAndStore(
                        703,
                        702,
                        "ocr",
                        "runs/703/ocr/a1/",
                        ocrResult(List.of(observation(0, 500, KEY_0, "강원도 대표 볼거리관", "강원도 대표 볼거리 관", 0.9981)), 2, true)))
                .isEmpty();

        var stored = storedObservations();
        assertThat(stored).hasSize(1);
        // 원문은 교정으로도 덮어쓰지 않는 값이고, `tokens` 는 질의와 같은 설정으로 만들어진
        // 색인 재료다. BE 가 어느 쪽도 다시 만들지 않는다는 것이 이 단언의 내용이다.
        assertThat(stored.getFirst())
                .containsEntry("raw_text", "강원도 대표 볼거리관")
                .containsEntry("tokens", "강원도 대표 볼거리 관")
                .containsEntry("storage_key", KEY_0);
        assertThat(((Number) stored.getFirst().get("confidence")).doubleValue()).isEqualTo(0.9981);
        assertThat((String) stored.getFirst().get("box")).contains("\"x\": 262").contains("\"height\": 80");
    }

    @Test
    void malformedLaterObservationDoesNotPartiallyStoreTheEarlierOne() throws Exception {
        storeKeyframes();

        // 관측 하나가 깨지면 그 프레임만 비는 것이 아니라 단계 전체가 실패한다. 먼저 넣고
        // 나중에 던지면 `complete` 가 거절된 run 에 관측 일부가 남아, 다시 읽을 방법이 없다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "ocr",
                        "runs/703/ocr/a1/",
                        ocrResult(
                                List.of(
                                        observation(0, 500, KEY_0, "강원도", "강원도", 0.99),
                                        observation(1, 9999, KEY_1, "없는 프레임", "없다", 0.98)),
                                2,
                                true)))
                .isInstanceOf(BusinessException.class);
        assertThat(storedObservations()).isEmpty();
    }

    @Test
    void acceptsAnObservationWhoseTextHasNoIndexTokens() throws Exception {
        storeKeyframes();

        // 기호뿐인 원문은 내용어를 남기지 않는다. 빈 토큰은 실패가 아니라 "색인할 것이
        // 없다" 는 사실이고, `ocr_observation.tokens` 는 NOT NULL 이지만 빈 문자열을 받는다.
        adapter.validateAndStore(
                703,
                702,
                "ocr",
                "runs/703/ocr/a1/",
                ocrResult(List.of(observation(0, 500, KEY_0, "▶", "", 0.71)), 2, true));

        assertThat(storedObservations()).singleElement().hasFieldOrPropertyWithValue("tokens", "");
    }

    @Test
    void rejectsAKeyframeCountThatDisagreesWithTheFramesThisRunStored() throws Exception {
        storeKeyframes();

        // "0장을 읽고 0건" 과 "23장을 읽고 0건" 을 가르는 값이다(계약 §4.3.2). OCR 은 모든
        // keyframe 을 읽으므로 많아도 적어도 안 된다 — 2장 중 1장만 읽고 죽다 만 워커가
        // `keyframesRead: 1, observations: []` 로 성공을 신고하면 "이 영상에는 화면 글자가
        // 없다" 는 거짓이 정본에 남는다.
        for (int read : new int[] {3, 1})
            assertThatThrownBy(() -> adapter.validateAndStore(
                            703, 702, "ocr", "runs/703/ocr/a1/", ocrResult(List.of(), read, true)))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e -> assertThat(e.errorCode().code()).isEqualTo("JOB_400_001"));
        // 글자가 없는 영상은 실패가 아니다. 읽은 장 수가 맞으면 관측 0건으로 통과한다.
        assertThat(adapter.validateAndStore(703, 702, "ocr", "runs/703/ocr/a1/", ocrResult(List.of(), 2, true)))
                .isEmpty();
    }

    @Test
    void rejectsAnOcrResultThatIsMissingOrDisagreesWithTheCompleteOutput() throws Exception {
        storeKeyframes();
        var read = List.of(observation(0, 500, KEY_0, "강원도", "강원도", 0.99));

        // 산출물이 없으면 `textGroups`·`mergeConfigVersion` 의 보관처가 사라진다. 그 둘은
        // `ocr_observation` 에 칸이 없어 파일이 유일한 정본이다(계약 §4.3.2).
        assertThatThrownBy(
                        () -> adapter.validateAndStore(703, 702, "ocr", "runs/703/ocr/a1/", ocrResult(read, 2, false)))
                .isInstanceOf(BusinessException.class);
        // 파일과 complete 가 갈리면 그룹의 관측 인덱스가 어느 배열을 가리키는지 알 수 없다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703, 702, "ocr", "runs/703/ocr/a1/", ocrResult(read, 2, true, stored -> {
                            var changed = new java.util.LinkedHashMap<>(stored);
                            changed.put("keyframesRead", 1);
                            return changed;
                        })))
                .isInstanceOf(BusinessException.class);
        assertThat(storedObservations()).isEmpty();

        adapter.validateAndStore(703, 702, "ocr", "runs/703/ocr/a1/", ocrResult(read, 2, true));
        assertThat(storedObservations()).hasSize(1);
    }

    @Test
    void rejectsASecondSetOfObservationsForTheSameRun() throws Exception {
        storeKeyframes();
        var read = List.of(observation(0, 500, KEY_0, "강원도", "강원도", 0.99));
        adapter.validateAndStore(703, 702, "ocr", "runs/703/ocr/a1/", ocrResult(read, 2, true));

        // 같은 run 에 두 번 저장되면 같은 글자가 두 근거로 남아 검색 결과가 중복된다
        // (`scenes()` 와 같은 불변식).
        assertThatThrownBy(
                        () -> adapter.validateAndStore(703, 702, "ocr", "runs/703/ocr/a1/", ocrResult(read, 2, true)))
                .isInstanceOf(BusinessException.class);
        assertThat(storedObservations()).hasSize(1);
    }

    private static Map<String, Object> frame(int sceneIndex, int timestampMs, String storageKey) {
        return Map.of("sceneIndex", sceneIndex, "timestampMs", timestampMs, "storageKey", storageKey);
    }

    /** v2 의 OCR 근거. `observationIndex` 는 원본 관측 배열의 위치이며 DB ID 가 아니다. */
    private static Map<String, Object> readText(int sceneIndex, int timestampMs, String key, int observationIndex) {
        return Map.of(
                "sourceRefType",
                "ocr_observation",
                "sceneIndex",
                sceneIndex,
                "timestampMs",
                timestampMs,
                "storageKey",
                key,
                "observationIndex",
                observationIndex);
    }

    /** v2 의 대사 근거. `storageKey` 는 원본 segments snapshot 이고 `segmentId` 는 그 안에서만 유일하다. */
    private static Map<String, Object> spoken(int sceneIndex, String segmentId, int s, int e) {
        return Map.of(
                "sourceRefType",
                "scene",
                "sceneIndex",
                sceneIndex,
                "storageKey",
                "runs/703/transcript_selection/a1/segments.json",
                "segmentId",
                segmentId,
                "s",
                s,
                "e",
                e,
                "sourceDetail",
                "asr");
    }

    private static Map<String, Object> judgement(String value, double confidence, Object... evidence) {
        return Map.of("value", value, "confidence", confidence, "evidence", List.of(evidence));
    }

    private static Map<String, Object> candidate(String type, String value, double confidence, Object... evidence) {
        return Map.of("type", type, "value", value, "confidence", confidence, "evidence", List.of(evidence));
    }

    /** 장면 하나의 VLM 결과. {@code caption} 이 {@code null} 이면 키를 싣지 않는다. */
    private static Map<String, Object> described(
            int sceneIndex, Map<String, Object> shotType, Map<String, Object> caption, Object... candidates) {
        var scene = new java.util.LinkedHashMap<String, Object>();
        scene.put("sceneIndex", sceneIndex);
        scene.put("shotType", shotType);
        if (caption != null) scene.put("caption", caption);
        scene.put("tagCandidates", List.of(candidates));
        return scene;
    }

    private static Map<String, Object> vlmResult(Object... scenes) {
        return body("vlm_metadata", Map.of("metadataSchemaVersion", "vlm-metadata/v2", "scenes", List.of(scenes)));
    }

    /** 캡션. 토큰은 워커가 만든 것을 그대로 쓴다 — BE 에 Kiwi 가 없다. */
    private static Map<String, Object> caption(String value, String tokens, double confidence, Object... evidence) {
        return Map.of("value", value, "tokens", tokens, "confidence", confidence, "evidence", List.of(evidence));
    }

    private List<Map<String, Object>> storedScenes() {
        return jdbc.queryForList("""
                SELECT caption, caption_tokens, shot_type FROM npick.scene
                WHERE pipeline_run_id=703 ORDER BY start_time_ms, scene_id
                """);
    }

    @Test
    void storesCaptionsShotTypesAndUnverifiedTagCandidates() throws Exception {
        storeKeyframes();

        assertThat(adapter.validateAndStore(
                        703,
                        702,
                        "vlm_metadata",
                        "runs/703/vlm_metadata/a1/",
                        vlmResult(
                                described(
                                        0,
                                        judgement("b_roll", 0.82, frame(0, 500, KEY_0)),
                                        caption("취재진이 모인 현장", "취재진 모이다 현장", 0.77, frame(0, 500, KEY_0)),
                                        candidate("scene_type", "사고 현장", 0.61, frame(0, 500, KEY_0))),
                                described(1, judgement("anchor", 0.9, frame(1, 1500, KEY_1)), null))))
                .isEmpty();

        var scenes = storedScenes();
        assertThat(scenes).hasSize(2);
        assertThat(scenes.getFirst())
                .containsEntry("caption", "취재진이 모인 현장")
                .containsEntry("caption_tokens", "취재진 모이다 현장")
                .containsEntry("shot_type", "b_roll");
        // 캡션은 없을 수 있다. 그래도 `shot_type` 은 NOT NULL 이라 늘 채워진다.
        assertThat(scenes.get(1))
                .containsEntry("shot_type", "anchor")
                .containsEntry("caption", null)
                .containsEntry("caption_tokens", null);

        // 표시값은 `name`, 매칭값은 NFKC + 공백 제거다. 둘을 섞으면 정확 일치 조회가 0건이 된다.
        assertThat(jdbc.queryForList("SELECT tag_type, match_value, name FROM npick.tag"))
                .containsExactly(Map.of("tag_type", "scene_type", "match_value", "사고현장", "name", "사고 현장"));
        var evidence = jdbc.queryForList("""
                SELECT e.source, e.verification_status, e.source_ref_type, e.confidence,
                       e.source_ref_id = k.keyframe_id AS points_at_the_frame, t.clip_id, t.scene_id IS NOT NULL AS scoped
                FROM npick.tag_evidence e JOIN npick.tagging t USING (tagging_id)
                JOIN npick.keyframe k ON k.storage_key = ?
                """, KEY_0);
        assertThat(evidence).singleElement().satisfies(row -> {
            // 신뢰도가 높다고 verified 로 올리지 않는다 (FRD §3 F-04). 사람의 승인은
            // `reviewer_feedback` 으로 따로 남는다.
            assertThat(row)
                    .containsEntry("source", "vlm")
                    .containsEntry("verification_status", "unverified")
                    .containsEntry("source_ref_type", "keyframe")
                    .containsEntry("points_at_the_frame", true)
                    .containsEntry("clip_id", 702L)
                    .containsEntry("scoped", true);
            assertThat(((Number) row.get("confidence")).doubleValue()).isEqualTo(0.61);
        });
    }

    @Test
    void storesEveryEvidenceOfATagCandidateUnderItsOwnSourceType() throws Exception {
        storeKeyframes();

        // 워커의 근거 배열은 이미지·OCR·대사가 섞인다(`TagCandidateOut.evidence`). 이 단계가
        // v2 로 올라간 사유가 뒤의 둘이라, 그것들을 버리면 태그의 출처가 사라진다.
        adapter.validateAndStore(
                703,
                702,
                "vlm_metadata",
                "runs/703/vlm_metadata/a1/",
                vlmResult(
                        described(
                                0,
                                judgement("b_roll", 0.82, frame(0, 500, KEY_0)),
                                null,
                                candidate(
                                        "person",
                                        "홍길동",
                                        0.8,
                                        frame(0, 500, KEY_0),
                                        readText(0, 500, KEY_0, 3),
                                        spoken(0, "s1", 0, 900))),
                        described(1, judgement("anchor", 0.9, frame(1, 1500, KEY_1)), null)));

        // OCR 근거도 프레임 참조를 실으므로 `keyframe` 으로 되돌린다. 관측 인덱스는 DB ID 가
        // 아니라 `ocr_result` 산출물이 정본이다. 대사는 계약대로 scene 근거로 간다.
        assertThat(jdbc.queryForList(
                        "SELECT source_ref_type, count(*) AS rows FROM npick.tag_evidence GROUP BY 1 ORDER BY 1"))
                .containsExactly(
                        Map.of("source_ref_type", "keyframe", "rows", 2L),
                        Map.of("source_ref_type", "scene", "rows", 1L));
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM npick.tag_evidence e JOIN npick.scene s ON s.scene_id = e.source_ref_id
                        WHERE e.source_ref_type='scene' AND s.pipeline_run_id=703
                        """, Long.class)).isEqualTo(1);
        // 근거가 셋이어도 태그와 tagging 은 하나다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.tagging", Long.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsAnEvidenceSourceTypeOutsideTheContract() throws Exception {
        storeKeyframes();

        for (Map<String, Object> broken : List.<Map<String, Object>>of(
                Map.of("sourceRefType", "montage", "sceneIndex", 0, "timestampMs", 500, "storageKey", KEY_0),
                // 모양이 맞아도 이 run 에 없는 장면을 가리키면 근거가 아니다.
                Map.of(
                        "sourceRefType",
                        "scene",
                        "sceneIndex",
                        9,
                        "storageKey",
                        "runs/703/transcript_selection/a1/segments.json",
                        "segmentId",
                        "s1",
                        "s",
                        0,
                        "e",
                        900,
                        "sourceDetail",
                        "asr")))
            assertThatThrownBy(() -> adapter.validateAndStore(
                            703,
                            702,
                            "vlm_metadata",
                            "runs/703/vlm_metadata/a1/",
                            vlmResult(
                                    described(0, judgement("unknown", 0.1), null),
                                    described(
                                            1,
                                            judgement("anchor", 0.9, frame(1, 1500, KEY_1)),
                                            null,
                                            candidate("keyword", "사고", 0.5, broken)))))
                    .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.tag", Long.class))
                .isZero();
    }

    @Test
    void usesOnlyImageEvidenceForTheShotType() throws Exception {
        storeKeyframes();

        // `shotType` 은 이미지 라벨만 사용한다(계약 §4.3.3). 화면 글자나 대사로 앵커·자료
        // 화면을 가르는 판단은 그 단계가 하는 일이 아니다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "vlm_metadata",
                        "runs/703/vlm_metadata/a1/",
                        vlmResult(
                                described(0, judgement("unknown", 0.1), null),
                                described(1, judgement("anchor", 0.9, spoken(1, "s1", 1000, 1900)), null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAShotTypeOutsideTheRankingVocabulary() throws Exception {
        storeKeyframes();

        // 랭킹이 매 검색마다 읽는 값이다(`b_roll` 보조 가산점). 모르는 값이 들어가면
        // 오류 없이 가산점만 빠져 증상이 검색 결과에만 나타난다. 위반을 **뒤** 장면에 두어
        // 앞 장면이 정상 데이터로 남지 않는지도 함께 본다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "vlm_metadata",
                        "runs/703/vlm_metadata/a1/",
                        vlmResult(
                                described(
                                        0,
                                        judgement("b_roll", 0.82, frame(0, 500, KEY_0)),
                                        caption("취재진이 모인 현장", "취재진 모이다 현장", 0.77, frame(0, 500, KEY_0))),
                                described(1, judgement("montage", 0.5, frame(1, 1500, KEY_1)), null))))
                .isInstanceOf(BusinessException.class);
        // `shot_type` 은 `scenes()` 가 넣은 기본값 그대로여야 한다. 캡션까지 함께 봐야
        // "아무것도 안 했다" 와 "`unknown` 을 썼다" 가 구분된다.
        assertThat(storedScenes())
                .allSatisfy(scene ->
                        assertThat(scene).containsEntry("shot_type", "unknown").containsEntry("caption", null));
    }

    @Test
    void rejectsADateTagCandidateWithoutStoringTheEarlierScene() throws Exception {
        storeKeyframes();

        // 화면에 날짜가 보인다는 사실과 그것이 방송일·촬영일이라는 판단은 다르다(FRD F-04).
        // 워커 schema 에 그 유형이 없으므로 여기 오는 것은 계약 위반이고, 뒤 장면의 위반이
        // 앞 장면의 캡션을 정상 데이터로 남겨서도 안 된다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "vlm_metadata",
                        "runs/703/vlm_metadata/a1/",
                        vlmResult(
                                described(
                                        0,
                                        judgement("b_roll", 0.82, frame(0, 500, KEY_0)),
                                        caption("취재진이 모인 현장", "취재진 모이다 현장", 0.77, frame(0, 500, KEY_0))),
                                described(
                                        1,
                                        judgement("anchor", 0.9, frame(1, 1500, KEY_1)),
                                        null,
                                        candidate("broadcast_date", "2026-09-16", 0.9, frame(1, 1500, KEY_1))))))
                .isInstanceOf(BusinessException.class);
        assertThat(storedScenes()).allSatisfy(scene -> assertThat(scene).containsEntry("caption", null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.tag", Long.class))
                .isZero();
    }

    @Test
    void acceptsAnEmptyEvidenceListOnlyForAnUnknownShotType() throws Exception {
        storeKeyframes();

        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "vlm_metadata",
                        "runs/703/vlm_metadata/a1/",
                        vlmResult(
                                described(0, judgement("b_roll", 0.82), null),
                                described(1, judgement("anchor", 0.9, frame(1, 1500, KEY_1)), null))))
                .isInstanceOf(BusinessException.class);

        // 쓸 값이 어휘 안에 있기 때문에 근거가 없어도 `shot_type` 은 채워진다(계약 §4.3.3).
        adapter.validateAndStore(
                703,
                702,
                "vlm_metadata",
                "runs/703/vlm_metadata/a1/",
                vlmResult(
                        described(0, judgement("unknown", 0.1), null),
                        described(1, judgement("anchor", 0.9, frame(1, 1500, KEY_1)), null)));
        assertThat(storedScenes().getFirst()).containsEntry("shot_type", "unknown");
    }

    @Test
    void rejectsASecondDescriptionForTheSameRun() throws Exception {
        storeKeyframes();
        var first = vlmResult(
                described(
                        0,
                        judgement("b_roll", 0.82, frame(0, 500, KEY_0)),
                        caption("취재진이 모인 현장", "취재진 모이다 현장", 0.77, frame(0, 500, KEY_0))),
                described(1, judgement("anchor", 0.9, frame(1, 1500, KEY_1)), null));
        adapter.validateAndStore(703, 702, "vlm_metadata", "runs/703/vlm_metadata/a1/", first);

        assertThatThrownBy(() -> adapter.validateAndStore(703, 702, "vlm_metadata", "runs/703/vlm_metadata/a1/", first))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsACaptionThatCarriesNoIndexTokens() throws Exception {
        storeKeyframes();

        // 게시 판정이 보는 것은 산문이 아니라 `scene.caption_tokens` 다
        // (`JdbcClipPublicationAdapter`). 토큰 없는 캡션을 받으면 설명이 붙은 클립이
        // 영원히 게시되지 않는다. BE 에 Kiwi 가 없어 여기서 만들어 줄 수도 없다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "vlm_metadata",
                        "runs/703/vlm_metadata/a1/",
                        vlmResult(
                                described(
                                        0,
                                        judgement("b_roll", 0.82, frame(0, 500, KEY_0)),
                                        caption("취재진이 모인 현장", "취재진 모이다 현장", 0.77, frame(0, 500, KEY_0))),
                                described(
                                        1,
                                        judgement("anchor", 0.9, frame(1, 1500, KEY_1)),
                                        Map.of(
                                                "value",
                                                "앵커가 말한다",
                                                "confidence",
                                                0.7,
                                                "evidence",
                                                List.of(frame(1, 1500, KEY_1)))))))
                .isInstanceOf(BusinessException.class);
        assertThat(storedScenes()).allSatisfy(scene -> assertThat(scene).containsEntry("caption", null));
    }

    @Test
    void rejectsTheV1EnvelopeOnTheTwoStagesThatMovedToV2() throws Exception {
        storeKeyframes();

        // 워커는 두 단계에 v2 를 싣는다. 봉투 문자열을 BE 가 따로 조립하면 배정 payload 와
        // complete 검사가 갈려, 저장 분기가 있어도 성공 결과가 거절된다(계약 §11-12).
        for (Map<String, Object> result : List.of(
                ocrResult(List.of(), 2, true),
                vlmResult(
                        described(0, judgement("unknown", 0.1), null),
                        described(1, judgement("unknown", 0.1), null)))) {
            var v1 = new java.util.LinkedHashMap<>(result);
            String stage = (String) result.get("stage");
            v1.put("versions", Map.of("outputSchemaVersion", "npick.stage." + stage + ".output/v1"));
            assertThatThrownBy(() -> adapter.validateAndStore(703, 702, stage, "runs/703/" + stage + "/a1/", v1))
                    .isInstanceOf(BusinessException.class);
        }
    }

    /** payload 와 산출물 양쪽에 같은 변형을 건다. 파일과 complete 가 갈리면 그룹 검증에 닿기 전에 거절된다. */
    private Map<String, Object> ocrWith(
            List<Map<String, Object>> observations, java.util.function.UnaryOperator<Map<String, Object>> change)
            throws Exception {
        return ocrResult(observations, 2, true, change, change);
    }

    private static Map<String, Object> replacing(Map<String, Object> output, String key, Object value) {
        var changed = new java.util.LinkedHashMap<>(output);
        changed.put(key, value);
        return changed;
    }

    @Test
    void rejectsTextGroupsThatCannotBeResolvedBackToTheObservations() throws Exception {
        storeKeyframes();
        var read =
                List.of(observation(0, 500, KEY_0, "강원도", "강원도", 0.99), observation(1, 1500, KEY_1, "속초", "속초", 0.80));

        // 그룹은 `observations` 배열의 인덱스이고 그 배열과 함께 `ocr_result` 에만 남는다.
        // 여기서 안 막으면 끊긴 참조가 유일한 정본에 그대로 굳고, 나중에 읽는 쪽이 깨진다.
        var broken = Map.<String, List<Map<String, Object>>>of(
                "범위 밖", List.of(group(0, List.of(0), 0), group(1, List.of(5), 5)),
                "중복", List.of(group(0, List.of(0), 0), group(1, List.of(0), 0)),
                "누락", List.of(group(0, List.of(0), 0)),
                "다른 scene", List.of(group(0, List.of(0, 1), 0)),
                "그룹 밖 대표", List.of(group(0, List.of(0), 1), group(1, List.of(1), 1)),
                "대표가 최대 신뢰도가 아님", List.of(group(0, List.of(0, 1), 1)));
        for (var entry : broken.entrySet())
            assertThatThrownBy(() -> adapter.validateAndStore(
                            703,
                            702,
                            "ocr",
                            "runs/703/ocr/a1/",
                            ocrWith(read, output -> replacing(output, "textGroups", entry.getValue()))))
                    .describedAs(entry.getKey())
                    .isInstanceOf(BusinessException.class);
        assertThat(storedObservations()).isEmpty();

        adapter.validateAndStore(703, 702, "ocr", "runs/703/ocr/a1/", ocrResult(read, 2, true));
        assertThat(storedObservations()).hasSize(2);
    }

    private static Map<String, Object> group(int sceneIndex, List<Integer> members, int representative) {
        return Map.of("sceneIndex", sceneIndex, "observationIndices", members, "representativeIndex", representative);
    }

    @Test
    void rejectsAnOcrOutputWithoutTheMergeConfigVersion() throws Exception {
        storeKeyframes();
        var read = List.of(observation(0, 500, KEY_0, "강원도", "강원도", 0.99));

        // 병합 설정의 식별자도 `ocr_observation` 에 칸이 없어 산출물이 유일한 보관처다.
        assertThatThrownBy(() -> adapter.validateAndStore(703, 702, "ocr", "runs/703/ocr/a1/", ocrWith(read, output -> {
                    var changed = new java.util.LinkedHashMap<>(output);
                    changed.remove("mergeConfigVersion");
                    return changed;
                })))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAConfidenceThatTheColumnWouldSilentlyRound() throws Exception {
        storeKeyframes();

        // `numeric(5,4)` 다. 다섯째 자리를 받으면 DB 가 반올림하고, 같은 트랜잭션에서
        // 보존한 `ocr_result` 에는 반올림 전 값이 남아 둘이 갈린다 — 파일과 payload 가
        // 같은지 확인하는 이 어댑터가 스스로 만드는 불일치다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "ocr",
                        "runs/703/ocr/a1/",
                        ocrResult(List.of(observation(0, 500, KEY_0, "강원도", "강원도", 0.99815)), 2, true)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "vlm_metadata",
                        "runs/703/vlm_metadata/a1/",
                        vlmResult(
                                described(0, judgement("b_roll", 0.82345, frame(0, 500, KEY_0)), null),
                                described(1, judgement("anchor", 0.9, frame(1, 1500, KEY_1)), null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsATagValueTooLongForTheColumn() throws Exception {
        storeKeyframes();

        // `tag.match_value` 는 varchar(255) 인데 워커 schema 의 `value` 에는 상한이 없다.
        // 여기서 안 막으면 INSERT 가 트랜잭션을 SQL 오류로 끊어, 워커가 받는 것은
        // JOB_400_001 이 아니라 500 이다 — 재시도 가능으로 분류돼 같은 자리를 반복한다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "vlm_metadata",
                        "runs/703/vlm_metadata/a1/",
                        vlmResult(
                                described(0, judgement("unknown", 0.1), null),
                                described(
                                        1,
                                        judgement("anchor", 0.9, frame(1, 1500, KEY_1)),
                                        null,
                                        candidate("keyword", "가".repeat(256), 0.5, frame(1, 1500, KEY_1))))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("JOB_400_001"));
    }

    @Test
    void rejectsAMetadataSchemaVersionThisAdapterCannotRead() throws Exception {
        storeKeyframes();

        // 봉투(`outputSchemaVersion`)와 다른 값이다. 이쪽은 모델에게 요구한 JSON 의 버전이고,
        // v1 은 텍스트 근거가 없어 근거 해석 규약이 다르다(계약 §4.3.3).
        var v1 = new java.util.LinkedHashMap<>(vlmResult(
                described(0, judgement("unknown", 0.1), null),
                described(1, judgement("anchor", 0.9, frame(1, 1500, KEY_1)), null)));
        v1.put("output", replacing((Map<String, Object>) v1.get("output"), "metadataSchemaVersion", "vlm-metadata/v1"));
        assertThatThrownBy(() -> adapter.validateAndStore(703, 702, "vlm_metadata", "runs/703/vlm_metadata/a1/", v1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsASecondDescriptionEvenWhenNothingWasCaptioned() throws Exception {
        storeKeyframes();
        // 캡션도 없고 `shot_type` 도 전부 기본값인 run 이다. 그래도 태그 후보는 올 수 있고,
        // 두 번 저장되면 `tagging` 은 접혀도 `tag_evidence` 는 그대로 두 행이 된다.
        var described = vlmResult(
                described(0, judgement("unknown", 0.1), null),
                described(1, judgement("unknown", 0.1), null, candidate("keyword", "사고", 0.5, frame(1, 1500, KEY_1))));
        adapter.validateAndStore(703, 702, "vlm_metadata", "runs/703/vlm_metadata/a1/", described);

        assertThatThrownBy(() ->
                        adapter.validateAndStore(703, 702, "vlm_metadata", "runs/703/vlm_metadata/a1/", described))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.tag_evidence", Long.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsACaptionThatIsNeitherAnObjectNorNull() throws Exception {
        storeKeyframes();

        // `caption: null` 만 "설명이 없는 장면" 이다. 문자열이나 숫자가 오면 모양이 틀린
        // 출력이고, 그것을 조용히 무시하면 설명이 있었는데 사라진 장면과 구분되지 않는다.
        var scene = new java.util.LinkedHashMap<String, Object>();
        scene.put("sceneIndex", 0);
        scene.put("shotType", judgement("unknown", 0.1));
        scene.put("caption", "취재진이 모인 현장");
        scene.put("tagCandidates", List.of());
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "vlm_metadata",
                        "runs/703/vlm_metadata/a1/",
                        vlmResult(scene, described(1, judgement("anchor", 0.9, frame(1, 1500, KEY_1)), null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAnOcrArtifactSetThatIsNotExactlyTheOneResultFile() throws Exception {
        storeKeyframes();
        var read = List.of(observation(0, 500, KEY_0, "강원도", "강원도", 0.99));

        // 이 단계가 올리는 파일은 하나다(계약 §4.3.2). 곁다리 참조가 함께 오면 그것이
        // 무엇인지 이 어댑터가 알지 못하고, 보존 대상인지도 계약에 없다.
        var extra = new java.util.LinkedHashMap<>(ocrResult(read, 2, true));
        byte[] bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String key = "runs/703/ocr/a1/stray.json";
        try (var upload = new LocalWorkerArtifactAdapter(root)
                .prepareUpload("runs/703/ocr/a1/", key, bytes.length, hash, new java.io.ByteArrayInputStream(bytes))) {
            upload.publish();
        }
        var artifacts = new java.util.ArrayList<Map<String, Object>>();
        for (Object ref : (List<?>) extra.get("artifacts")) artifacts.add((Map<String, Object>) ref);
        artifacts.add(Map.of("kind", "keyframe", "storageKey", key, "byteSize", bytes.length, "contentHash", hash));
        extra.put("artifacts", artifacts);
        assertThatThrownBy(() -> adapter.validateAndStore(703, 702, "ocr", "runs/703/ocr/a1/", extra))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAnOcrResultFileMissingItsIdentityOrThreshold() throws Exception {
        storeKeyframes();
        var read = List.of(observation(0, 500, KEY_0, "강원도", "강원도", 0.99));

        // 재현 튜플이 없으면 어떤 설정이 이 관측을 만들었는지 파일만 보고는 알 수 없다.
        assertThatThrownBy(() -> adapter.validateAndStore(
                        703,
                        702,
                        "ocr",
                        "runs/703/ocr/a1/",
                        ocrResult(
                                read,
                                2,
                                true,
                                java.util.function.UnaryOperator.identity(),
                                java.util.function.UnaryOperator.identity(),
                                false)))
                .isInstanceOf(BusinessException.class);
        // `minConfidence` 는 미검증 관측을 가르는 임계값이다(`ocr_observation.confidence` 주석).
        assertThatThrownBy(() -> adapter.validateAndStore(703, 702, "ocr", "runs/703/ocr/a1/", ocrWith(read, output -> {
                    var changed = new java.util.LinkedHashMap<>(output);
                    changed.remove("minConfidence");
                    return changed;
                })))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void foldsTheSameTagOnOneSceneIntoOneTaggingWithBothEvidences() throws Exception {
        storeKeyframes();

        // 같은 장면에 같은 태그가 두 번 오면 `tagging` 은 UNIQUE 로 접히고 근거만 늘어난다.
        adapter.validateAndStore(
                703,
                702,
                "vlm_metadata",
                "runs/703/vlm_metadata/a1/",
                vlmResult(
                        described(0, judgement("unknown", 0.1), null),
                        described(
                                1,
                                judgement("anchor", 0.9, frame(1, 1500, KEY_1)),
                                null,
                                candidate("keyword", "사고", 0.5, frame(1, 1500, KEY_1)),
                                candidate("keyword", "사 고", 0.4, spoken(1, "s1", 1000, 1900)))));

        // 표기가 달라도 `match_value` 가 같아 태그는 하나다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.tag", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.tagging", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT source_ref_type FROM npick.tag_evidence ORDER BY 1"))
                .containsExactly(Map.of("source_ref_type", "keyframe"), Map.of("source_ref_type", "scene"));
    }

    @Test
    void supportsTheTwoStagesThatStalledTheRunAtItsThirdStep() {
        // `supports()` 가 거짓인 동안 `ocr` 은 claim capabilities 에서 지워져 배정되지
        // 않았고, 3단계가 pending 인 run 은 하류 7개 단계에 도달하지 못했다(계약 §11-12).
        assertThat(adapter.supports("ocr")).isTrue();
        assertThat(adapter.supports("vlm_metadata")).isTrue();
        // 워커도 BE 도 없는 단계는 여전히 거짓이다. 저장 자리가 없는 성공 결과를 받지 않는다.
        assertThat(adapter.supports("scene_transcript_mapping")).isFalse();
        assertThat(adapter.supports("entity_extraction")).isFalse();
    }
}
