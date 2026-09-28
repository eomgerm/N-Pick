package com.npick.pipeline.infrastructure.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.TsidGenerator;
import com.npick.pipeline.application.error.WorkerIntegrationErrorCode;
import com.npick.pipeline.application.port.TagVocabularyPort;
import com.npick.pipeline.application.port.WorkerArtifactPort;
import com.npick.pipeline.application.port.WorkerArtifactPort.Ref;
import com.npick.pipeline.domain.model.PipelineStages;
import com.npick.pipeline.infrastructure.artifact.LocalWorkerArtifactAdapter;

/** #36 StageOutputPort method binding. Does not claim, transition, retry or publish a run. */
public class JdbcWorkerStageOutputAdapter implements com.npick.pipeline.application.port.StageOutputPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorkerArtifactPort artifacts;
    private final TagVocabularyPort vocabulary;

    /**
     * {@code <모델>@<40자리 hex>}. Must stay identical to the pattern the dense reader enforces in SQL
     * ({@code DenseSceneCandidateAdapter}); a vector this side accepts and that side rejects is invisible everywhere
     * except an empty dense result.
     */
    private static final String PINNED_MODEL_VERSION = "[^\\s@]+@[0-9a-f]{40}";

    @Override
    public boolean supports(String stage) {
        return java.util.Set.of(
                        "scene_detection",
                        "frame_extraction",
                        "ocr",
                        "transcript_selection",
                        "asr",
                        "scene_transcript_mapping",
                        "vlm_metadata",
                        "entity_extraction",
                        "text_embedding",
                        "indexing")
                .contains(stage);
    }

    public JdbcWorkerStageOutputAdapter(
            JdbcTemplate jdbc, ObjectMapper mapper, WorkerArtifactPort artifacts, TagVocabularyPort vocabulary) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.artifacts = artifacts;
        this.vocabulary = vocabulary;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> validateAndStore(
            long runId, long clipId, String stage, String prefix, Map<String, Object> result) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("stage output must join the fenced completion transaction");
        if (!prefix.matches("runs/" + runId + "/" + stage + "/a[1-9][0-9]*/")) invalid();
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM npick.pipeline_run WHERE pipeline_run_id=? AND clip_id=?",
                        Long.class,
                        runId,
                        clipId)
                != 1) invalid();
        JsonNode body = mapper.valueToTree(result);
        if (!"succeeded".equals(text(body, "status"))
                || !stage.equals(text(body, "stage"))
                // 단계마다 다르다. 문자열을 여기서 조립하면 v2 단계(`ocr`·`vlm_metadata`)의
                // 성공 결과가 저장 분기에 닿기도 전에 거절된다 — 배정 payload 는 그 표가 만든다.
                || !text(body.path("versions"), "outputSchemaVersion").equals(PipelineStages.outputSchema(stage)))
            invalid();
        var refs = references(body.path("artifacts"), prefix);
        JsonNode output = body.path("output");
        if (!output.isObject() || output.isEmpty()) invalid();
        return switch (stage) {
            case "scene_detection" -> scenes(runId, clipId, output);
            case "frame_extraction" -> keyframes(runId, output, refs);
            case "ocr" -> observations(runId, output, refs);
            case "transcript_selection" -> {
                validateTranscript(runId, output.path("transcript"), refs);
                yield Map.of();
            }
            case "asr" -> {
                var ids = new HashSet<String>();
                for (JsonNode segment : array(output, "segments")) {
                    validateSegment(segment);
                    if (!"asr".equals(text(segment, "sourceDetail")) || !ids.add(text(segment, "segmentId"))) invalid();
                }
                yield Map.of();
            }
            case "scene_transcript_mapping" -> transcripts(runId, output, body.path("versions"), refs);
            case "vlm_metadata" -> captions(runId, clipId, output);
            case "entity_extraction" -> entities(runId, clipId, output);
            case "text_embedding" -> embeddings(runId, output, body.path("versions"), refs);
            case "indexing" -> {
                summarised(runId, output);
                yield Map.of();
            }
            // No unimplemented schema/storage adapter may accept a successful result.
            default -> throw new BusinessException(WorkerIntegrationErrorCode.INVALID_OUTPUT);
        };
    }

    private Map<String, Ref> references(JsonNode values, String prefix) {
        if (!values.isArray()) invalid();
        Map<String, Ref> refs = new HashMap<>();
        for (JsonNode value : values) {
            Ref ref = ref(value);
            LocalWorkerArtifactAdapter.validateKey(ref.storageKey());
            if (!ref.storageKey().startsWith(prefix) || refs.put(ref.storageKey(), ref) != null) invalid();
            artifacts.verify(ref);
        }
        return refs;
    }

    private Map<String, Object> scenes(long run, long clip, JsonNode output) {
        var values = array(output, "scenes");
        if (values.isEmpty()) invalid();
        long duration = integer(output, "mediaDurationMs", 1);
        if (!output.path("frameRate").isNumber()
                || !Double.isFinite(output.path("frameRate").doubleValue())
                || output.path("frameRate").doubleValue() <= 0) invalid();
        long end = 0;
        int index = 0;
        for (JsonNode scene : values) {
            if (integer(scene, "sceneIndex", 0) != index++ || integer(scene, "startTimeMs", 0) != end) invalid();
            long next = integer(scene, "endTimeMs", 1);
            if (next <= end) invalid();
            end = next;
        }
        if (end != duration
                || jdbc.queryForObject("SELECT count(*) FROM npick.scene WHERE pipeline_run_id=?", Long.class, run)
                        != 0) invalid();
        List<Map<String, Object>> ids = new ArrayList<>();
        for (JsonNode scene : values) {
            long id = TsidGenerator.generate();
            jdbc.update("""
                    INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms,
                        shot_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'unknown', now(), now())
                    """, id, clip, run, integer(scene, "startTimeMs", 0), integer(scene, "endTimeMs", 1));
            ids.add(Map.of("sceneIndex", integer(scene, "sceneIndex", 0), "sceneId", Long.toString(id)));
        }
        return Map.of("scenes", ids);
    }

    private Map<String, Object> keyframes(long run, JsonNode output, Map<String, Ref> refs) {
        integer(output, "imageWidth", 1);
        integer(output, "imageHeight", 1);
        var scenes = jdbc.queryForList("""
                SELECT scene_id, start_time_ms, end_time_ms FROM npick.scene
                WHERE pipeline_run_id=? ORDER BY start_time_ms, scene_id
                """, run);
        var values = array(output, "scenes");
        if (scenes.isEmpty() || values.size() != scenes.size()) invalid();
        var indexes = new HashSet<Long>();
        var keys = new HashSet<String>();
        var inserts = new ArrayList<Object[]>();
        for (JsonNode scene : values) {
            long index = integer(scene, "sceneIndex", 0);
            if (index >= scenes.size() || !indexes.add(index)) invalid();
            var stored = scenes.get((int) index);
            var frames = array(scene, "keyframes");
            if (frames.isEmpty()
                    || integer(frames.getFirst(), "timestampMs", 0) != integer(scene, "representativeTimestampMs", 0))
                invalid();
            var timestamps = new HashSet<Long>();
            for (JsonNode frame : frames) {
                long timestamp = integer(frame, "timestampMs", 0);
                String key = text(frame, "storageKey");
                if (integer(frame, "sceneIndex", 0) != index
                        || !timestamps.add(timestamp)
                        || !keys.add(key)
                        || timestamp < ((Number) stored.get("start_time_ms")).longValue()
                        || timestamp >= ((Number) stored.get("end_time_ms")).longValue()
                        || !refs.containsKey(key)
                        || !refs.get(key).kind().equals("keyframe")) invalid();
                inserts.add(new Object[] {TsidGenerator.generate(), stored.get("scene_id"), timestamp, key});
            }
        }
        // Preserve wire order: first frame receives the smallest generated ID for that scene.
        for (Object[] valuesToInsert : inserts)
            jdbc.update(
                    "INSERT INTO npick.keyframe (keyframe_id, scene_id, timestamp_ms, storage_key) VALUES (?, ?, ?, ?)",
                    valuesToInsert);
        return Map.of();
    }

    /**
     * Fill {@code scene.embedding}. The vectors live in the uploaded artifact, not in the payload: a 1024-float vector
     * per scene would push {@code stage_states_json} into megabytes, and that column travels whole on every run read.
     */
    private Map<String, Object> embeddings(long run, JsonNode output, JsonNode versions, Map<String, Ref> refs) {
        Ref ref = registered(output.path("embeddingsArtifact"), "scene_embeddings", refs);
        JsonNode document = artifactJson(ref);
        // **어느 가중치가 만든 벡터인지 고정 리비전으로 말해야 한다.** 이 형식은 dense
        // 리더가 강제하는 것과 같다(`DenseSceneCandidateAdapter`) — 어긋난 벡터는 저장돼도
        // `missing_model` 로 후보에서 전량 제외되고, 그 제외는 정본·요약·채널 상태 어디에도
        // 드러나지 않는다. `dimension` 과 달리 조용히 죽는다는 점이 여기서 막는 이유다.
        if (!text(versions.path("detail"), "modelVersion").matches(PINNED_MODEL_VERSION)) invalid();
        long dimension = integer(output, "dimension", 1);
        // 컬럼 폭을 카탈로그에서 읽는다. payload·artifact·벡터 길이는 셋 다 워커가 만드는
        // 값이라 서로 맞는 것만으로는 아무것도 보장하지 않는다 — 설정만 768 로 바꾸고
        // 마이그레이션을 두면 셋이 사이좋게 통과하고 UPDATE 가 SQL 오류로 트랜잭션을 끊는다.
        // 그러면 워커가 받는 것은 JOB_400_001 이 아니라 500 이고, 그 응답은 재시도 가능으로
        // 분류돼 같은 자리에서 lease 만료 → 재배정을 반복한다. 상수로 박지 않는 이유는
        // 이 버그 자체가 두 곳의 어긋남이기 때문이다. 세 번째 자리를 만들지 않는다.
        if (!"npick.scene.embeddings/v1".equals(text(document, "schemaVersion"))
                || integer(document, "dimension", 1) != dimension
                || storedEmbeddingDimension() != dimension) invalid();
        // scene_index 는 keyframes() 와 같은 순서 규약으로 scene_id 에 대응한다.
        var scenes = jdbc.queryForList(
                "SELECT scene_id FROM npick.scene WHERE pipeline_run_id=? ORDER BY start_time_ms, scene_id", run);
        if (scenes.isEmpty()) invalid();
        var embedded = new LinkedHashMap<Long, String>();
        for (JsonNode scene : array(document, "scenes")) {
            long index = integer(scene, "sceneIndex", 0);
            JsonNode values = scene.path("vector");
            // 길이를 여기서 막지 않으면 vector(1024) 컬럼이 트랜잭션 전체를 SQL 오류로 끊는다.
            if (index >= scenes.size() || !values.isArray() || values.size() != dimension) invalid();
            var vector = new StringBuilder("[");
            for (JsonNode value : values) {
                // NaN·inf 가 저장되면 pgvector 의 코사인 거리가 정의되지 않아 그 장면이
                // 모든 질의에서 조용히 빠진다. 증상이 검색 결과에만 나타나 원인이 멀다.
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())) invalid();
                if (vector.length() > 1) vector.append(',');
                vector.append(value.doubleValue());
            }
            text(scene, "sourceText");
            if (embedded.put(index, vector.append(']').toString()) != null) invalid();
        }
        if (embedded.size() != integer(output, "embeddedCount", 0)) invalid();
        var skipped = new HashSet<Long>();
        for (JsonNode value : array(output, "skippedSceneIndexes")) {
            // canConvertToLong() 없이 longValue() 를 부르면 범위를 넘는 값에서 Jackson 이
            // 던지고, 그 예외는 BusinessException 이 아니라 500 으로 나간다.
            if (!value.isIntegralNumber() || !value.canConvertToLong()) invalid();
            long index = value.longValue();
            if (index < 0 || index >= scenes.size() || embedded.containsKey(index) || !skipped.add(index)) invalid();
        }
        // 모든 장면이 둘 중 하나로 설명돼야 한다. 워커가 장면을 흘렸을 때 잡히는 유일한 불변식이다.
        if (embedded.size() + skipped.size() != scenes.size()) invalid();
        for (var entry : embedded.entrySet())
            jdbc.update(
                    "UPDATE npick.scene SET embedding=?::vector, updated_at=now() WHERE scene_id=?",
                    entry.getValue(),
                    scenes.get(entry.getKey().intValue()).get("scene_id"));
        return Map.of();
    }

    /**
     * Check the indexing summary against the stored scenes. Nothing is written: the index materials were persisted by
     * the upstream stages and the BM25/pgvector indexes belong to the migration. Publication readiness is decided by
     * {@code JdbcClipPublicationAdapter}, never here.
     */
    private void summarised(long run, JsonNode output) {
        long scenes = jdbc.queryForObject("SELECT count(*) FROM npick.scene WHERE pipeline_run_id=?", Long.class, run);
        if (scenes == 0 || integer(output, "sceneCount", 1) != scenes) invalid();
        for (String channel : List.of("captionedScenes", "dialogueScenes", "ocrScenes", "embeddedScenes"))
            if (integer(output, channel, 0) > scenes) invalid();
        // `embeddedScenes` 만 BE 가 정본과 직접 맞춰 볼 수 있다. 나머지 세 채널의 재료는
        // 아직 어느 어댑터도 쓰지 않으므로 대조할 행이 없다(계약 §11-12).
        if (integer(output, "embeddedScenes", 0)
                != jdbc.queryForObject(
                        "SELECT count(*) FROM npick.scene WHERE pipeline_run_id=? AND embedding IS NOT NULL",
                        Long.class,
                        run)) invalid();
    }

    /** The {@code scene.embedding} column width, read from the catalog so it cannot drift from the migration. */
    private long storedEmbeddingDimension() {
        // queryForObject 는 행이 없으면 null 이 아니라 EmptyResultDataAccessException 을 던지고,
        // 그 예외는 BusinessException 을 우회해 500 으로 나간다 — embeddings() 위 주석이
        // 막으려던 재배정 루프 그 경로다. 행 없음을 여기서 직접 다룬다.
        var typmod = jdbc.queryForList(
                "SELECT atttypmod FROM pg_attribute WHERE attrelid='npick.scene'::regclass AND attname='embedding'",
                Integer.class);
        // **invalid() 를 쓰지 않는다.** 그것은 JOB_400_001(영구, 워커 과실)이라 워커가 자기
        // 출력을 의심하며 단계를 실패로 닫는데, 여기 걸리는 원인은 BE 스키마다. 고칠 사람도
        // 볼 로그도 다르다. 트랜잭션 가드와 같은 등급으로 올린다.
        if (typmod.size() != 1 || typmod.getFirst() == null || typmod.getFirst() <= 0)
            throw new IllegalStateException("scene.embedding must be declared with a fixed vector dimension");
        return typmod.getFirst();
    }

    private Map<String, Object> observations(long run, JsonNode output, Map<String, Ref> refs) {
        preserved(output, refs);
        var keyframes = keyframeIds(run, sceneIds(run));
        // 상류 없이 빈 결과를 내지 않는다 — "이 영상에는 화면 글자가 없다" 는 거짓이 정본에 남는다.
        // OCR 은 모든 keyframe 을 읽으므로(§4.3.2) 읽은 장 수가 모자라면 중간에 죽은 것이고,
        // 그 결과를 성공으로 저장하면 같은 거짓이 남는다.
        if (keyframes.isEmpty()
                || integer(output, "keyframesRead", 0) != keyframes.size()
                || jdbc.queryForObject("""
                                SELECT count(*) FROM npick.ocr_observation o JOIN npick.keyframe k USING (keyframe_id)
                                JOIN npick.scene s USING (scene_id) WHERE s.pipeline_run_id=?
                                """, Long.class, run) != 0) invalid();
        var read = array(output, "observations");
        groups(output, read);
        var inserts = new ArrayList<Object[]>();
        for (JsonNode observation : read) {
            Long keyframe = keyframes.get(integer(observation, "sceneIndex", 0) + ":"
                    + integer(observation, "timestampMs", 0) + ":" + text(observation, "storageKey"));
            JsonNode box = observation.path("boundingBox");
            // 내용어가 없는 원문(기호뿐인 자막)은 토큰을 남기지 않는다. 빈 문자열이 정상 값이라
            // `text()` 를 쓰지 않는다 — 그것은 blank 를 거절한다.
            if (keyframe == null
                    || !box.isObject()
                    || box.isEmpty()
                    || !observation.path("tokens").isTextual()) invalid();
            inserts.add(new Object[] {
                TsidGenerator.generate(),
                keyframe,
                text(observation, "rawText"),
                observation.path("tokens").asText(),
                // 범위와 자릿수를 한자리에서 본다. 인라인으로 다시 쓰면 `numeric(5,4)` 가드가
                // `groups()` 의 전수 커버에만 기대게 되고, 그 불변식은 세 메서드 건너에 있다.
                confidence(observation),
                box.toString()
            });
        }
        // 검증을 모두 마친 뒤에 쓴다. 뒤 관측이 깨졌는데 앞 관측만 남으면 거절된 run 에
        // 반쪽 근거가 남고, 그 프레임을 다시 읽을 방법이 없다(`keyframes()` 와 같은 규약).
        for (Object[] values : inserts) jdbc.update("""
                    INSERT INTO npick.ocr_observation (ocr_observation_id, keyframe_id, raw_text, tokens,
                        confidence, bounding_box_json) VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    """, values);
        return Map.of();
    }

    private Map<String, Object> captions(long run, long clip, JsonNode output) {
        var scenes = sceneIds(run);
        var keyframes = keyframeIds(run, scenes);
        var values = array(output, "scenes");
        // 봉투와 다른 값이다 — 이쪽은 모델에게 요구한 JSON 의 버전이고, v1 은 텍스트 근거가
        // 없어 근거 해석 규약이 다르다(계약 §4.3.3).
        if (!"vlm-metadata/v2".equals(text(output, "metadataSchemaVersion"))) invalid();
        // 장면 하나가 빠지면 그 장면은 "설명이 없는 장면" 으로 저장돼 실패가 정상 데이터로 보인다.
        // 이미 설명이 붙은 run 에 다시 쓰지도 않는다 — `tagging` 은 UNIQUE 로 접히지만
        // `tag_evidence` 는 그대로 쌓여 같은 태그가 두 근거로 남는다. 캡션도 없고 `shot_type`
        // 도 전부 기본값인 run 이 있으므로(근거가 없으면 `unknown` 이다) 태그까지 함께 본다.
        if (scenes.isEmpty() || values.size() != scenes.size() || jdbc.queryForObject("""
                                SELECT count(*) FROM npick.scene s WHERE s.pipeline_run_id=?
                                  AND (s.caption IS NOT NULL OR s.shot_type <> 'unknown'
                                       OR EXISTS (SELECT 1 FROM npick.tagging t JOIN npick.tag_evidence e USING (tagging_id)
                                                  WHERE t.scene_id = s.scene_id AND e.source = 'vlm'))
                                """, Long.class, run) != 0)
            invalid();
        var indexes = new HashSet<Long>();
        var described = new ArrayList<Object[]>();
        var candidates = new ArrayList<Object[]>();
        for (JsonNode scene : values) {
            long index = integer(scene, "sceneIndex", 0);
            if (index >= scenes.size() || !indexes.add(index)) invalid();
            Object sceneId = scenes.get((int) index).get("scene_id");
            JsonNode shot = scene.path("shotType");
            String shotType = text(shot, "value");
            if (!SHOT_TYPES.contains(shotType)) invalid();
            confidence(shot);
            // 근거 없이 내려도 되는 판단은 `unknown` 하나다(계약 §4.3.3). 나머지는 무엇을
            // 보고 그렇게 말했는지가 결과 화면의 근거가 된다. `shotType` 은 그중에서도
            // 이미지 라벨만 쓴다 — 화면 글자나 대사로 앵커·자료화면을 가르지 않는다.
            // 워커가 말한 종류를 본다. 저장값은 OCR 근거도 `keyframe` 이라 여기서는 구분이 안 된다.
            for (Object[] evidence : evidences(shot, index, keyframes, scenes, shotType.equals("unknown")))
                if (!"keyframe".equals(evidence[2])) invalid();
            JsonNode caption = scene.path("caption");
            // `null` 과 없음만 "설명이 없는 장면" 이다. 문자열·숫자를 조용히 무시하면
            // 모양이 틀린 출력이 설명 없는 장면으로 위장된다.
            if (!caption.isObject() && !caption.isNull() && !caption.isMissingNode()) invalid();
            // 게시 판정이 보는 것은 산문이 아니라 토큰이다(`JdbcClipPublicationAdapter`).
            // BE 에 Kiwi 가 없어 없는 토큰을 여기서 만들어 줄 수도 없다.
            if (caption.isObject()) {
                confidence(caption);
                evidences(caption, index, keyframes, scenes, false);
                if (!caption.path("tokens").isTextual()) invalid();
            }
            described.add(new Object[] {
                caption.isObject() ? text(caption, "value") : null,
                caption.isObject() ? caption.path("tokens").asText() : null,
                shotType,
                sceneId
            });
            for (JsonNode candidate : array(scene, "tagCandidates")) {
                String type = text(candidate, "type");
                candidates.add(new Object[] {
                    sceneId,
                    type,
                    // 유형·표기 검증을 쓰기 앞에서 끝낸다. 뒤 장면의 위반이 앞 장면의 태그를
                    // 정상 데이터로 남기지 않는다.
                    matchValue(type, text(candidate, "value")),
                    text(candidate, "value"),
                    confidence(candidate),
                    evidences(candidate, index, keyframes, scenes, false)
                });
            }
        }
        for (Object[] values0 : described)
            jdbc.update(
                    "UPDATE npick.scene SET caption=?, caption_tokens=?, shot_type=?, updated_at=now() WHERE scene_id=?",
                    values0);
        storeCandidates(clip, "vlm", candidates);
        return Map.of();
    }

    /**
     * 검증을 마친 장면 태그 후보를 세 표로 옮긴다.
     *
     * <p>한 행은 {@code {scene_id, tag_type, match_value, name, confidence, 근거 행 목록}} 이다. <b>자동 후보는 늘 장면 범위이고 검증되지 않은 상태로
     * 고정한다</b>(FR-PRC-052·055) — 높은 신뢰도는 verified 의 근거가 아니고, 사람의 승인은 {@code reviewer_feedback} 으로 따로 남는다.
     */
    private void storeCandidates(long clip, String source, List<Object[]> candidates) {
        for (Object[] candidate : folded(candidates)) {
            long tagId = tagId((String) candidate[1], (String) candidate[2], (String) candidate[3]);
            long taggingId = TsidGenerator.generate();
            // 같은 장면에 같은 태그가 두 번 오면 근거만 늘린다. UNIQUE 가 NULLS NOT DISTINCT 라
            // clip 범위 태그도 같은 규칙으로 접힌다.
            jdbc.update("""
                    INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)
                    VALUES (?, ?, ?, ?, now()) ON CONFLICT (clip_id, scene_id, tag_id) DO NOTHING
                    """, taggingId, clip, candidate[0], tagId);
            // 근거 하나가 한 행이다. 워커가 근거를 여러 개 주는 이유는 한 판단이 여러 곳을
            // 보고 나왔기 때문이고, 서로 다른 곳을 가리키는 근거를 하나로 접으면 나머지가
            // 흔적 없이 사라진다. **같은 곳을 가리키게 되는 근거만 접는다** — 같은 프레임의
            // 이미지 근거와 OCR 근거는 저장하면 `evidence_id` 빼고 모든 칸이 같아, 두 행이
            // 서로 다른 무엇을 가리키는지 조회로 답할 수 없다(관측 단위는 `ocr_result` 가
            // 정본이다). 같은 이유로 이 단계의 결과가 두 번 들어와도 근거가 두 배가 되지 않는다.
            @SuppressWarnings("unchecked")
            var evidences = (List<Object[]>) candidate[5];
            for (Object[] evidence : evidences)
                jdbc.update(
                        """
                        INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence,
                            verification_status, source_ref_type, source_ref_id, created_at)
                        SELECT ?, t.tagging_id, ?, ?, 'unverified', ?, ?, now() FROM npick.tagging t
                        WHERE t.clip_id=? AND t.scene_id=? AND t.tag_id=?
                          AND NOT EXISTS (SELECT 1 FROM npick.tag_evidence e WHERE e.tagging_id=t.tagging_id
                                            AND e.source=? AND e.source_ref_type=? AND e.source_ref_id=?)
                        """,
                        TsidGenerator.generate(),
                        source,
                        candidate[4],
                        evidence[0],
                        evidence[1],
                        clip,
                        candidate[0],
                        tagId,
                        source,
                        evidence[0],
                        evidence[1]);
        }
    }

    /**
     * 같은 {@code (장면, 유형, match_value)} 가 된 후보를 하나로 합친다 — 최초 표시값, 최대 신뢰도, 근거를 이은 것.
     *
     * <p>워커가 자기 안에서 쓰는 규칙과 같다(계약 §4.3.6). <b>표기 정규화가 워커와 BE 에서 다르기 때문에 필요하다.</b> 7단계는 정규화 <i>전</i> 표기로만 후보를 합치고
     * ({@code vlm_metadata/validator.py} 의 {@code _deduplicate}) 8단계 워커는 공백과 세 글자만 지우는 자체 키를 쓰는데({@code extractor.py} 의
     * {@code match_key}), {@code TagMatchValue} 는 {@code \p{Cf}} 전체와 CGJ 까지 지운다. 그래서 워커에서 갈라져 온 두 후보가 BE 에서 같은 태그가 된다.
     * 그때 뒤 후보를 그냥 버리면 신뢰도가 조용히 낮은 쪽으로 굳는다.
     *
     * <p>이은 근거에 같은 참조가 섞여 있어도 여기서 걸러내지 않는다 — 같은 곳을 가리키게 되는 근거를 한 행으로 접는 일은 INSERT 가 이미 한다 ({@code storeCandidates()}).
     * 같은 규칙을 두 군데 두면 둘이 어긋나는 날이 온다.
     *
     * <p>입력 배열은 고치지 않는다. 제자리에서 고치면 호출자가 그 목록을 다시 쓰는 날 신뢰도가 이미 덮여 있다.
     */
    private static List<Object[]> folded(List<Object[]> candidates) {
        var merged = new LinkedHashMap<List<Object>, Object[]>();
        for (Object[] candidate : candidates) {
            Object[] previous = merged.get(List.of(candidate[0], candidate[1], candidate[2]));
            if (previous == null) {
                var evidences = new ArrayList<Object[]>();
                evidences.addAll(cast(candidate[5]));
                merged.put(
                        List.of(candidate[0], candidate[1], candidate[2]),
                        new Object[] {candidate[0], candidate[1], candidate[2], candidate[3], candidate[4], evidences});
                continue;
            }
            previous[4] = Math.max((Double) previous[4], (Double) candidate[4]);
            cast(previous[5]).addAll(cast(candidate[5]));
        }
        return List.copyOf(merged.values());
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> cast(Object evidences) {
        return (List<Object[]>) evidences;
    }

    /**
     * 8단계 장면 태그 후보를 저장한다 (계약 §4.3.6).
     *
     * <p><b>{@code source} 가 {@code vlm} 인 후보는 쓰지 않는다.</b> 그것은 이 단계가 만든 판단이 아니라 BE 가 {@code inputs.upstream} 으로 돌려준 7단계
     * 후보를 그대로 되실은 것이고, 같은 값이 {@code captions()} 에서 이미 저장됐다. 다시 넣으면 {@code tagging} 은 UNIQUE 로 접히지만 {@code tag_evidence}
     * 는 그대로 쌓여 같은 태그가 같은 근거를 두 번 갖는다. 검증은 그대로 한다 — 되싣는 길에 망가진 후보를 조용히 버리면 7단계와 8단계가 무엇을 주고받았는지 어긋난 채로 성공한다.
     */
    private Map<String, Object> entities(long run, long clip, JsonNode output) {
        var scenes = sceneIds(run);
        var keyframes = keyframeIds(run, scenes);
        var values = array(output, "scenes");
        // 장면 하나가 빠지면 그 장면은 "후보가 없는 장면" 으로 굳는다. 빈 후보 배열은 정상이라
        // 저장된 결과만 봐서는 둘을 가를 수 없다.
        //
        // 이미 이 단계가 쓴 run 에 다시 쓰지도 않는다. **이 가드는 `rule` 행이 남았을 때만
        // 운다** — 후보가 0건이면 남길 흔적이 없고, `captions()` 의 `shot_type <> 'unknown'`
        // 같은 모양 기반 marker 가 이 단계에는 없다. 그 사각이 데이터를 망가뜨리지 않는 이유는
        // 근거 INSERT 가 같은 참조를 두 번 쓰지 않기 때문이고(`storeCandidates()`), 서로 다른
        // 결과가 두 번 들어오는 것은 `complete` 의 상태 전이가 먼저 막는다
        // (`PipelineRun.complete()` 는 `running` 인 단계만 받는다).
        if (scenes.isEmpty() || values.size() != scenes.size() || jdbc.queryForObject("""
                                SELECT count(*) FROM npick.tag_evidence e JOIN npick.tagging t USING (tagging_id)
                                JOIN npick.scene s ON s.scene_id = t.scene_id
                                WHERE s.pipeline_run_id=? AND e.source='rule'
                                """, Long.class, run) != 0)
            invalid();
        var indexes = new HashSet<Long>();
        var rows = new ArrayList<Object[]>();
        for (JsonNode scene : values) {
            long index = integer(scene, "sceneIndex", 0);
            if (index >= scenes.size() || !indexes.add(index)) invalid();
            Object sceneId = scenes.get((int) index).get("scene_id");
            for (JsonNode candidate : array(scene, "tagCandidates")) {
                String source = text(candidate, "source");
                // 어휘는 둘뿐이다. 입력이 OCR·CC 여도 출처는 바뀌지 않는다 — 그 값은 대사 원본의
                // 종류일 뿐이고, 여기서 넓히면 사람 입력·검수 판단과 같은 칸을 쓰게 된다.
                if (!"rule".equals(source) && !"vlm".equals(source)) invalid();
                String type = text(candidate, "type");
                String value = text(candidate, "value");
                // 분류 유형은 화면을 본 판단이라 VLM 입력 후보만 전달한다(계약 §4.3.6). 워커의
                // `Candidate.scope` 가 막는 것을 이쪽도 막는다 — 한쪽만 알면 그 차이가 곧 구멍이다.
                if ("rule".equals(source) && CLASSIFICATION_TYPES.contains(type)) invalid();
                // 유형·표기 검증을 쓰기 앞에서 끝낸다. 뒤 장면의 위반이 앞 장면의 태그를
                // 정상 데이터로 남기지 않는다. 날짜 유형은 포트가 거절한다.
                Object[] row = new Object[] {
                    sceneId,
                    type,
                    matchValue(type, value),
                    value,
                    confidence(candidate),
                    evidences(candidate, index, keyframes, scenes, false)
                };
                if ("rule".equals(source)) rows.add(row);
            }
        }
        storeCandidates(clip, "rule", rows);
        return Map.of();
    }

    /** 화면을 보고 내리는 분류 유형. 자체 NER 이 낼 수 있는 값이 아니라 VLM 후보로만 온다(계약 §4.3.6). */
    private static final java.util.Set<String> CLASSIFICATION_TYPES =
            java.util.Set.of("season", "weather", "scene_type");

    /** {@code scene.shot_type} 의 어휘. 랭킹이 매 검색마다 읽는 값이라 모르는 값이 들어가면 조용히 가산점만 빠진다. */
    private static final java.util.Set<String> SHOT_TYPES =
            java.util.Set.of("anchor", "interview", "b_roll", "unknown");

    /**
     * 0~1 이고 소수 넷째 자리까지다.
     *
     * <p>{@code numeric(5,4)} 라 다섯째 자리를 받으면 DB 가 반올림한다. 그러면 워커의 기록·{@code ocr_result} 산출물과 저장된 값이 갈리는데, 산출물과 payload 가
     * 같은지 확인하는 어댑터가 그 불일치를 스스로 만드는 꼴이 된다. 워커도 보내기 전에 맞춘다({@code CONFIDENCE_DECIMALS}).
     */
    private static double confidence(JsonNode judgement) {
        JsonNode value = judgement.path("confidence");
        if (!value.isNumber()
                || !(value.doubleValue() >= 0 && value.doubleValue() <= 1)
                || value.decimalValue().stripTrailingZeros().scale() > 4) invalid();
        return value.doubleValue();
    }

    /**
     * 판단의 근거를 {@code {저장할 source_ref_type, source_ref_id, 워커가 말한 종류}} 로 푼다.
     *
     * <p>v2 의 근거는 셋이다 — 이미지, OCR 관측, 대사(계약 §4.3.3). <b>OCR 근거도 프레임 참조를 실으므로 저장은 {@code keyframe} 으로 되돌린다.</b>
     * {@code observationIndex} 는 payload 배열의 위치이고 DB ID 가 아니어서, 그것을 {@code ocr_observation_id} 로 바꾸려면 이 어댑터의 INSERT
     * 순서에만 기대는 암묵 규약이 하나 더 생긴다. 관측으로 되돌리는 길은 {@code ocr_result} 산출물이 이미 정본이다(§4.3.2).
     *
     * <p><b>세 번째 칸이 원래 종류를 들고 있는 이유</b> — 저장값만 보면 이미지와 OCR 이 구분되지 않아, "이미지 라벨만" 을 요구하는 {@code shotType} 이 화면 글자로 내린 판단을
     * 걸러내지 못한다.
     *
     * <p><b>대사 근거의 구간 참조는 이 표에 넣지 않는다.</b> {@code segmentId}·{@code s}·{@code e}·{@code sourceDetail} 을 담을 칸이
     * {@code tag_evidence} 에 없어, 근거를 조회하면 "이 장면의 대사를 보고 나왔다" 까지만 답할 수 있다. 값이 사라지지는 않는다 — 단계 응답의 {@code output} 은
     * {@code stage_states_json} 에 통째로 보존되므로({@code PipelineRun}) 그 run 을 열면 꺼낼 수 있고, 조인·검색에 쓸 자리가 없을 뿐이다. 칸을 늘리는 것은 표
     * 변경이라 이 티켓의 범위가 아니며, §4.3.3 에 같은 사실을 적어 두었다.
     *
     * <p><b>근거는 판단이 달린 장면만 가리킨다</b>({@code scene} 인자). 워커는 장면마다 그 장면의 라벨만 주고 남의 장면 참조를 거절하지만
     * ({@code vlm_metadata/validator.py}, {@code entity_extraction/schema.py} 의 {@code same_scene}), 여기서 막지 않으면 장면 A 의
     * 태그가 장면 B 의 참조를 달고 굳는다. 저장되는 것은 참조 ID 뿐이라 나중에 가려낼 방법이 없고, 그 출력이 {@code inputs.upstream} 으로 8단계에 돌아가면 그 클립의 8단계가 매
     * 시도 영구 실패한다.
     */
    private List<Object[]> evidences(
            JsonNode judgement,
            long scene,
            Map<String, Long> keyframes,
            List<Map<String, Object>> scenes,
            boolean mayBeEmpty) {
        var values = array(judgement, "evidence");
        // 호출자 넷이 모두 먼저 보는 값이라 지금은 참이 되지 않는다. 다섯 번째 호출자가 그것을
        // 잊었을 때 `scenes.get()` 의 500(워커는 재시도 가능으로 읽는다) 대신 400 이 나가게 남긴다.
        if (scene >= scenes.size() || (values.isEmpty() && !mayBeEmpty)) invalid();
        var rows = new ArrayList<Object[]>();
        for (JsonNode evidence : values) {
            if (integer(evidence, "sceneIndex", 0) != scene) invalid();
            // 이미지 근거에는 이 키가 없다. 기존 객체 모양을 유지하기 위해서다(§4.3.3).
            String kind = evidence.has("sourceRefType") ? text(evidence, "sourceRefType") : "keyframe";
            switch (kind) {
                case "keyframe", "ocr_observation" -> {
                    Long keyframe = keyframes.get(
                            scene + ":" + integer(evidence, "timestampMs", 0) + ":" + text(evidence, "storageKey"));
                    if (keyframe == null) invalid();
                    rows.add(new Object[] {"keyframe", keyframe, kind});
                }
                case "scene" -> {
                    text(evidence, "segmentId");
                    rows.add(new Object[] {"scene", scenes.get((int) scene).get("scene_id"), kind});
                }
                default -> invalid();
            }
        }
        return rows;
    }

    /**
     * 유형을 확인하고 {@code tag.match_value} 를 만든다. 쓰기는 하지 않는다 — 뒤 장면의 위반이 앞 장면의 태그를 남기지 않도록 검증을 저장 앞에서 끝낸다.
     *
     * <p>판정 자체는 {@link TagVocabularyPort} 가 한다({@code tag} 모듈). 정규화 규칙은 백엔드에 하나뿐인 {@code TagMatchValue} 하나이고, 여기서 그것을
     * 직접 부르면 모듈 고리가 닫힌다. 그 포트가 {@code null} 을 주면 이쪽에서 잘못된 단계 출력으로 번역한다 — 어휘 밖 유형은 태그 오류가 아니라 워커가 계약을 어긴 것이다.
     */
    private String matchValue(String type, String value) {
        String match = vocabulary.matchValue(type, value);
        if (match == null) invalid();
        return match;
    }

    /** 공유 태그를 확보하고 {@code tag_id} 를 돌려준다. 동시 등록도 UNIQUE 위반으로 트랜잭션을 깨지 않는다. */
    private long tagId(String type, String match, String name) {
        jdbc.update("""
                INSERT INTO npick.tag (tag_id, tag_type, match_value, name) VALUES (?, ?, ?, ?)
                ON CONFLICT (tag_type, match_value) DO NOTHING
                """, TsidGenerator.generate(), type, match, name);
        return jdbc.queryForObject(
                "SELECT tag_id FROM npick.tag WHERE tag_type=? AND match_value=?", Long.class, type, match);
    }

    /**
     * 병합 그룹이 관측 배열로 되돌아가는지 확인한다 (계약 §4.3.2).
     *
     * <p>그룹은 {@code observations} 배열의 0-based 인덱스이고 {@code ocr_observation} 에는 그 칸이 없다. 즉 이 참조가 사는 곳은
     * {@code ocr_result} 산출물 하나뿐이라, 여기서 막지 않으면 끊긴 참조가 유일한 정본에 그대로 굳는다. 모든 관측은 정확히 한 그룹에 속하고, 한 그룹은 같은 scene 의 서로 다른
     * frame 만 담으며, 대표는 구성원 중 confidence 가 최대인 것을 가리킨다.
     */
    private void groups(JsonNode output, List<JsonNode> observations) {
        // 병합 설정의 식별자도 관측 표에 칸이 없다. 산출물이 유일한 보관처다.
        text(output, "mergeConfigVersion");
        // 미검증 관측을 가르는 임계값이다(`ocr_observation.confidence` 주석).
        JsonNode threshold = output.path("minConfidence");
        if (!threshold.isNumber() || !(threshold.doubleValue() >= 0 && threshold.doubleValue() <= 1)) invalid();
        var grouped = new HashSet<Long>();
        for (JsonNode group : array(output, "textGroups")) {
            long scene = integer(group, "sceneIndex", 0);
            var members = array(group, "observationIndices");
            if (members.isEmpty()) invalid();
            var frames = new HashSet<Long>();
            var indexes = new HashSet<Long>();
            double best = -1;
            for (JsonNode member : members) {
                if (!member.isIntegralNumber() || !member.canConvertToLong()) invalid();
                long index = member.longValue();
                if (index < 0 || index >= observations.size() || !grouped.add(index) || !indexes.add(index)) invalid();
                JsonNode observation = observations.get((int) index);
                if (integer(observation, "sceneIndex", 0) != scene
                        || !frames.add(integer(observation, "timestampMs", 0))) invalid();
                best = Math.max(best, confidence(observation));
            }
            long representative = integer(group, "representativeIndex", 0);
            if (!indexes.contains(representative) || confidence(observations.get((int) representative)) != best)
                invalid();
        }
        if (grouped.size() != observations.size()) invalid();
    }

    /**
     * OCR v2 의 JSON 보존 산출물을 확인한다 (계약 §4.3.2).
     *
     * <p>{@code textGroups} 와 {@code mergeConfigVersion} 은 {@code ocr_observation} 에 담을 칸이 없고 별도 그룹 표도 만들지 않으므로, 이 파일이
     * 둘의 유일한 보관처다. 그룹은 원본 관측 배열의 <b>인덱스</b>라 파일과 complete 의 배열이 갈리는 순간 그 참조가 무엇을 가리키는지 알 수 없게 된다.
     */
    private void preserved(JsonNode output, Map<String, Ref> refs) {
        // 이 단계가 올리는 파일은 하나다. 곁다리 참조가 함께 오면 그것이 무엇인지,
        // 보존 대상인지 계약에 없다.
        if (refs.size() != 1) invalid();
        Ref result = refs.values().iterator().next();
        if (!result.kind().equals("ocr_result")) invalid();
        JsonNode document = artifactJson(result);
        // `identity` 는 §7 의 OCR 재현 튜플이다. 없으면 어떤 설정이 이 관측을 만들었는지
        // 파일만 보고는 알 수 없고, 그 파일이 그룹의 유일한 정본이다.
        if (!PipelineStages.outputSchema("ocr").equals(text(document, "outputSchemaVersion"))
                || !document.path("identity").isObject()
                || document.path("identity").isEmpty()
                || !document.path("output").equals(output)) invalid();
    }

    /** 이 run 의 장면을 {@code sceneIndex} 순서로. 워커는 이 순서의 위치로 장면을 가리킨다. */
    private List<Map<String, Object>> sceneIds(long run) {
        return jdbc.queryForList(
                "SELECT scene_id FROM npick.scene WHERE pipeline_run_id=? ORDER BY start_time_ms, scene_id", run);
    }

    /**
     * {@code sceneIndex:timestampMs:storageKey} → {@code keyframe_id}.
     *
     * <p>세 값이 모두 맞아야 찾힌다. 워커는 {@code keyframe_id} 를 모르고 {@code (sceneIndex, timestampMs)} 로 프레임을 가리키며 파일 이름을 함께 에코한다(계약
     * §4.3.2·§4.3.3). 그 쌍만 대조하면 워커가 A 프레임에서 읽은 글자를 B 프레임의 것으로 신고해도 통과하는데, 저장되는 것은 {@code keyframe_id} 뿐이라 잘못 붙은 근거를 나중에
     * 가려낼 방법이 없다. {@code sceneIndex} 는 {@code keyframes()} 와 같은 순서 규약이다.
     */
    private Map<String, Long> keyframeIds(long run, List<Map<String, Object>> scenes) {
        Map<Long, Integer> indexes = new HashMap<>();
        for (int index = 0; index < scenes.size(); index++)
            indexes.put(((Number) scenes.get(index).get("scene_id")).longValue(), index);
        Map<String, Long> result = new HashMap<>();
        for (var row : jdbc.queryForList("""
                SELECT k.keyframe_id, k.scene_id, k.timestamp_ms, k.storage_key FROM npick.keyframe k
                JOIN npick.scene s USING (scene_id) WHERE s.pipeline_run_id=?
                """, run))
            result.put(
                    indexes.get(((Number) row.get("scene_id")).longValue()) + ":" + row.get("timestamp_ms") + ":"
                            + row.get("storage_key"),
                    ((Number) row.get("keyframe_id")).longValue());
        return result;
    }

    /**
     * {@code scene} 의 대사 네 칸을 채운다 (계약 §4.5).
     *
     * <p><b>토큰은 워커가 만든 것을 그대로 넣는다.</b> BE 에 Kiwi 가 없고 색인과 질의가 같은 설정을 써야 하며, 다르면 검색이 오류 없이 0건이 된다
     * ({@code scene.transcript_tokens} 주석). {@code transcript_text} 는 워커가 토큰을 만들 때 쓴 것과 같은 순서·같은 구분자(공백 한 칸)로 잇는다 — 두
     * 칸이 같은 문장을 가리켜야 한다.
     */
    private Map<String, Object> transcripts(long run, JsonNode output, JsonNode versions, Map<String, Ref> refs) {
        JsonNode transcript = output.path("transcript");
        Ref segmentsRef = registered(transcript.path("segmentsArtifact"), "transcript_segments", refs);
        JsonNode segmentsDocument = artifactJson(segmentsRef);
        JsonNode decisionsDocument =
                artifactJson(registered(transcript.path("decisionsArtifact"), "transcript_decisions", refs));
        // **두 파일이 같은 짝인지 본다.** 구간 ID 는 snapshot 사이에 보존되므로, 판정만 4단계
        // `transcript_selection` 의 예비 파일을 가리켜도 모든 ID 가 해석되고 아무것도 실패하지
        // 않는다 — 그 예비 판정이 최종으로 되살아난다(계약 §4.5). `validateTranscript` 와 같은 검사다.
        if (!"npick.transcript.segments/v1".equals(text(segmentsDocument, "schemaVersion"))
                || !"npick.transcript.decisions/v1".equals(text(decisionsDocument, "schemaVersion"))
                || !segmentsRef.equals(ref(decisionsDocument.path("segmentsArtifact")))) invalid();
        Map<String, JsonNode> originals = new LinkedHashMap<>();
        for (JsonNode segment : array(segmentsDocument, "segments")) {
            validateSegment(segment);
            if (originals.put(text(segment, "segmentId"), segment) != null) invalid();
        }
        // 채택 판정은 상류 소유다. 다시 계산하지 않고 읽기만 한다 — 보관 전용 구간이 연결되면
        // 채택하지 않은 자막으로 장면이 검색된다(계약 §4.5). 다만 판정이 모든 원본 ID 에 정확히
        // 하나씩 있어야 읽은 결과가 상류가 실제로 주장한 집합이다. 중복은 `selected=true` 쪽이
        // 조용히 이기고, 판정 없는 구간은 조용히 미채택으로 흐른다.
        Map<String, JsonNode> adopted = adoptions(decisionsDocument, originals);
        // 색인과 질의가 같은 Kiwi 설정을 써야 하고 어긋나면 검색이 조용히 0건이 된다. 그때 어느
        // 설정이 이 토큰을 만들었는지 되짚을 근거가 이 축 하나뿐이다(계약 §4.5) — `embeddings()`
        // 가 `modelVersion` 을 요구하는 것과 같은 자리다.
        text(versions.path("detail"), "tokenizer");
        var scenes = jdbc.queryForList("""
                SELECT scene_id, start_time_ms, end_time_ms FROM npick.scene
                WHERE pipeline_run_id=? ORDER BY start_time_ms, scene_id
                """, run);
        var values = array(output, "scenes");
        // 상류가 발급한 이 회차의 장면 전부가 정확히 한 번씩이어야 한다. 빠진 장면은 "대사 없음"
        // 으로 굳고, 중복 장면은 뒤 연결이 앞 연결을 조용히 덮는다.
        if (scenes.isEmpty() || values.size() != scenes.size()) invalid();
        var indexes = new HashSet<Long>();
        var updates = new ArrayList<Object[]>();
        for (JsonNode scene : values) {
            long index = integer(scene, "sceneIndex", 0);
            // 빈 문자열이 정상 값이라 `text()` 를 쓰지 않는다 — 그것은 blank 를 거절한다.
            // 내용어가 없는 대사는 원문이 있어도 토큰이 비고, 키 생략과는 구분해야 한다.
            if (index >= scenes.size()
                    || !indexes.add(index)
                    || !scene.path("tokens").isTextual()) invalid();
            var stored = scenes.get((int) index);
            var links = array(scene, "segments");
            if (links.isEmpty()) {
                // 연결이 없는데 토큰이 있다는 것은 스스로 모순인 출력이다. 조용히 버리면
                // 워커의 결함이 "대사 없는 장면" 으로 위장된다.
                if (!scene.path("tokens").asString().isEmpty()) invalid();
                continue;
            }
            long sceneStart = ((Number) stored.get("start_time_ms")).longValue();
            long sceneEnd = ((Number) stored.get("end_time_ms")).longValue();
            var texts = new ArrayList<String>();
            var spans = mapper.createArrayNode();
            boolean provided = false;
            long previous = -1;
            var linked = new HashSet<String>();
            for (JsonNode link : links) {
                JsonNode original = adopted.get(text(link, "segmentId"));
                // 같은 구간이 두 번 들어오면 원문도 `transcript_json` 의 구간도 겹쳐 적힌다.
                if (original == null || !linked.add(text(link, "segmentId"))) invalid();
                long start = integer(original, "s", 0);
                long end = integer(original, "e", 1);
                long overlap = integer(link, "overlapMs", 1);
                // **겹침은 계산되는 값이지 신고받는 값이 아니다.** `transcript_json.overlap_ms` 의
                // 정의가 "이 장면과 겹친 시간" 이라, 지어낸 값이나 장면과 겹치지도 않는 구간의
                // 연결이 그대로 정본에 남는다. 연결 순서는 화면에 보여주는 `transcript_text` 와
                // `transcript_json` 이 대사 순서대로 읽히도록 시간순이어야 한다 — 토큰과 갈리지는
                // 않는다. 워커가 같은 목록을 같은 순서로 이어 토큰을 만들기 때문이다.
                if (overlap != Math.min(sceneEnd, end) - Math.max(sceneStart, start) || start < previous) invalid();
                previous = start;
                texts.add(text(original, "t"));
                provided |= !"asr".equals(text(original, "sourceDetail"));
                spans.addObject()
                        .put("s", start)
                        .put("e", end)
                        .put("t", text(original, "t"))
                        .put("overlap_ms", overlap);
            }
            updates.add(new Object[] {
                String.join(" ", texts),
                storable(scene.path("tokens").asString()),
                spans.toString(),
                // 장면 하나가 자막과 ASR 을 함께 쓰면 제공 자막이 이긴다 — 영상 단위 판정과 같은
                // 방향이다(`JdbcClipPublicationAdapter.activate`).
                provided ? "provided" : "asr",
                stored.get("scene_id")
            });
        }
        // 대사가 없는 장면은 칸을 비운 채 둔다. 게시 판정이 공백 여부를 보므로 빈 문자열과 없음이 다르다.
        for (Object[] update : updates) jdbc.update("""
                    UPDATE npick.scene SET transcript_text=?, transcript_tokens=?, transcript_json=?::jsonb,
                        transcript_source=?, updated_at=now() WHERE scene_id=?
                    """, update);
        return Map.of();
    }

    private void validateTranscript(long run, JsonNode transcript, Map<String, Ref> refs) {
        Ref segmentsRef = registered(transcript.path("segmentsArtifact"), "transcript_segments", refs);
        Ref decisionsRef = registered(transcript.path("decisionsArtifact"), "transcript_decisions", refs);
        JsonNode segments = artifactJson(segmentsRef);
        JsonNode decisions = artifactJson(decisionsRef);
        if (!"npick.transcript.segments/v1".equals(text(segments, "schemaVersion"))
                || !"npick.transcript.decisions/v1".equals(text(decisions, "schemaVersion"))
                || !segmentsRef.equals(ref(decisions.path("segmentsArtifact")))) invalid();
        Map<String, JsonNode> originals = new LinkedHashMap<>();
        for (JsonNode segment : array(segments, "segments")) {
            validateSegment(segment);
            if (originals.put(text(segment, "segmentId"), segment) != null) invalid();
        }
        String prepared = jdbc.queryForObject(
                "SELECT stage_states_json->'stages'->'transcript_selection'->'preparedTranscript'->'segmentsArtifact' FROM npick.pipeline_run WHERE pipeline_run_id=?",
                String.class,
                run);
        if (prepared == null) invalid();
        JsonNode input = artifactJson(ref(mapper.readTree(prepared)));
        // Verify preservation, without choosing sources or recomputing selection policy.
        var inputIds = new HashSet<String>();
        for (JsonNode original : array(input, "segments")) {
            if (!inputIds.add(text(original, "segmentId"))) invalid();
            if (!original.equals(originals.get(text(original, "segmentId")))) invalid();
        }
        if (!originals.keySet().equals(inputIds)) invalid();
        adoptions(decisions, originals);
        if (!transcript.path("asrRequired").isBoolean()) invalid();
        var ranges = array(transcript, "candidateRanges");
        for (JsonNode range : ranges) if (integer(range, "e", 1) <= integer(range, "s", 0)) invalid();
        String reason = text(transcript, "reasonCode");
        if (!List.of("SUBTITLE_COVERED", "UNCOVERED_RANGES", "NO_VALID_SUBTITLE")
                .contains(reason)) invalid();
        if (transcript.path("asrRequired").booleanValue() != !ranges.isEmpty()
                || reason.equals("SUBTITLE_COVERED") != ranges.isEmpty()) invalid();
    }

    /**
     * {@code decisions} 가 원본 집합에 대해 완전하고 일관적인지 확인하고 <b>채택 집합</b>을 돌려준다 (계약 §4.5).
     *
     * <p>선택 정책을 다시 계산하지 않는다 — 정책은 상류 소유다. 여기서 보는 것은 판정이 스스로 모순인지뿐이다: 모든 원본 ID 에 정확히 하나씩, 채택 사유는 출처와 맞고 제외 근거를 달지 않으며,
     * 제외 사유는 하나뿐이고 근거는 <b>같은 snapshot 에서 채택된 상위 출처</b>이면서 실제로 시간이 겹쳐야 한다.
     *
     * <p><b>4단계와 이 단계가 같은 검사를 쓴다.</b> {@code ProcessingRecordReader.selectedSources} 가 두 snapshot 중 살아 있는 쪽을 같은 규칙으로
     * 읽으므로, 저장 쪽이 느슨하면 대사는 저장되는데 처리 상세는 자막을 {@code unavailable} 로 보고한다 — 화면과 정본이 갈린다.
     */
    private static Map<String, JsonNode> adoptions(JsonNode decisions, Map<String, JsonNode> originals) {
        var values = array(decisions, "decisions");
        // **채택 집합을 먼저 완성한다.** 판정 순서는 임의라, 훑으면서 그때까지 모인 채택만 보면
        // 뒤에 오는 채택을 근거로 든 제외가 순서에 따라 통과하기도 하고 아니기도 한다.
        Map<String, JsonNode> adopted = new LinkedHashMap<>();
        var seen = new HashSet<String>();
        for (JsonNode decision : values) {
            String id = text(decision, "segmentId");
            if (!originals.containsKey(id)
                    || !seen.add(id)
                    || !decision.path("selected").isBoolean()) invalid();
            if (decision.path("selected").booleanValue()) adopted.put(id, originals.get(id));
        }
        if (!seen.equals(originals.keySet())) invalid();
        for (JsonNode decision : values) {
            JsonNode original = originals.get(text(decision, "segmentId"));
            var conflicts = array(decision, "conflictsWith");
            String reason = text(decision, "reasonCode");
            if (decision.path("selected").booleanValue()) {
                String expected =
                        text(original, "sourceDetail").equals("asr") ? "ASR_SUPPLEMENT" : "PREFERRED_SUBTITLE";
                if (!expected.equals(reason) || !conflicts.isEmpty()) invalid();
            } else if (!reason.equals("OVERLAPS_HIGHER_PRIORITY") || conflicts.isEmpty()) invalid();
            var conflictIds = new HashSet<String>();
            for (JsonNode conflict : conflicts) {
                if (!conflict.isTextual() || !conflictIds.add(conflict.asText())) invalid();
                // **근거는 채택된 것만이다.** 원본에 있기만 하면 통과시키면 제외된 구간을 근거로
                // 또 다른 구간을 연쇄 제외할 수 있다 — 제외된 CC 가 자막 공백의 ASR 을 밀어내는
                // 그 경우이고, 계약 §4.5 가 이름을 붙여 금지한 자리다.
                JsonNode other = adopted.get(conflict.asText());
                if (other == null
                        || priority(other) >= priority(original)
                        || Math.max(integer(other, "s", 0), integer(original, "s", 0))
                                >= Math.min(integer(other, "e", 1), integer(original, "e", 1))) invalid();
            }
        }
        return adopted;
    }

    private static int priority(JsonNode segment) {
        return List.of("uploaded", "embedded", "asr").indexOf(text(segment, "sourceDetail"));
    }

    private JsonNode artifactJson(Ref ref) {
        try {
            return mapper.reader()
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(artifacts.verified(ref));
        } catch (tools.jackson.core.JacksonException failure) {
            throw new BusinessException(WorkerIntegrationErrorCode.INVALID_OUTPUT, failure);
        }
    }

    private static void validateSegment(JsonNode segment) {
        text(segment, "segmentId");
        text(segment, "t");
        if (integer(segment, "e", 1) <= integer(segment, "s", 0) || priority(segment) < 0) invalid();
    }

    private static Ref registered(JsonNode node, String kind, Map<String, Ref> refs) {
        Ref ref = ref(node);
        if (!kind.equals(ref.kind()) || !ref.equals(refs.get(ref.storageKey()))) invalid();
        return ref;
    }

    private static Ref ref(JsonNode node) {
        return new Ref(
                text(node, "kind"), text(node, "storageKey"), integer(node, "byteSize", 0), text(node, "contentHash"));
    }

    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank()) invalid();
        return storable(node.path(key).asText());
    }

    /**
     * NUL 이 든 문자열은 저장 앞에서 거절한다.
     *
     * <p>PostgreSQL 의 {@code text} 도 {@code jsonb} 도 {@code U+0000} 을 받지 못한다. 여기서 막지 않으면 드라이버 예외가
     * {@link BusinessException} 을 우회해 500 으로 나가고, 그 응답은 재시도 가능으로 분류돼 같은 자리에서 lease 만료 → 재배정을 반복한다 ({@code embeddings()}
     * 의 컬럼 폭 주석과 같은 경로다).
     */
    private static String storable(String value) {
        if (value.indexOf(0) >= 0) invalid();
        return value;
    }

    private static long integer(JsonNode node, String key, long minimum) {
        var value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) invalid();
        return value.longValue();
    }

    private static List<JsonNode> array(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isArray()) invalid();
        List<JsonNode> items = new ArrayList<>();
        value.forEach(items::add);
        return items;
    }

    private static void invalid() {
        throw new BusinessException(WorkerIntegrationErrorCode.INVALID_OUTPUT);
    }
}
