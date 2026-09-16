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
import com.npick.pipeline.application.port.WorkerArtifactPort;
import com.npick.pipeline.application.port.WorkerArtifactPort.Ref;
import com.npick.pipeline.domain.model.PipelineStages;
import com.npick.pipeline.infrastructure.artifact.LocalWorkerArtifactAdapter;
import com.npick.tag.domain.model.TagMatchValue;
import com.npick.tag.domain.model.TagType;

/** #36 StageOutputPort method binding. Does not claim, transition, retry or publish a run. */
public class JdbcWorkerStageOutputAdapter implements com.npick.pipeline.application.port.StageOutputPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorkerArtifactPort artifacts;

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
                        "vlm_metadata",
                        "text_embedding",
                        "indexing")
                .contains(stage);
    }

    public JdbcWorkerStageOutputAdapter(JdbcTemplate jdbc, ObjectMapper mapper, WorkerArtifactPort artifacts) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.artifacts = artifacts;
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
            case "vlm_metadata" -> captions(runId, clipId, output);
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
            JsonNode confidence = observation.path("confidence");
            // 내용어가 없는 원문(기호뿐인 자막)은 토큰을 남기지 않는다. 빈 문자열이 정상 값이라
            // `text()` 를 쓰지 않는다 — 그것은 blank 를 거절한다.
            if (keyframe == null
                    || !box.isObject()
                    || box.isEmpty()
                    || !observation.path("tokens").isTextual()
                    || !confidence.isNumber()
                    || !(confidence.doubleValue() >= 0 && confidence.doubleValue() <= 1)) invalid();
            inserts.add(new Object[] {
                TsidGenerator.generate(),
                keyframe,
                text(observation, "rawText"),
                observation.path("tokens").asText(),
                confidence.doubleValue(),
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
                                       OR EXISTS (SELECT 1 FROM npick.tagging t WHERE t.scene_id = s.scene_id))
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
            for (Object[] evidence : evidences(shot, keyframes, scenes, shotType.equals("unknown")))
                if (!"keyframe".equals(evidence[0])) invalid();
            JsonNode caption = scene.path("caption");
            // `null` 과 없음만 "설명이 없는 장면" 이다. 문자열·숫자를 조용히 무시하면
            // 모양이 틀린 출력이 설명 없는 장면으로 위장된다.
            if (!caption.isObject() && !caption.isNull() && !caption.isMissingNode()) invalid();
            // 게시 판정이 보는 것은 산문이 아니라 토큰이다(`JdbcClipPublicationAdapter`).
            // BE 에 Kiwi 가 없어 없는 토큰을 여기서 만들어 줄 수도 없다.
            if (caption.isObject()) {
                confidence(caption);
                evidences(caption, keyframes, scenes, false);
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
                    evidences(candidate, keyframes, scenes, false)
                });
            }
        }
        for (Object[] values0 : described)
            jdbc.update(
                    "UPDATE npick.scene SET caption=?, caption_tokens=?, shot_type=?, updated_at=now() WHERE scene_id=?",
                    values0);
        for (Object[] candidate : candidates) {
            long tagId = tagId((String) candidate[1], (String) candidate[2], (String) candidate[3]);
            long taggingId = TsidGenerator.generate();
            // 같은 장면에 같은 태그가 두 번 오면 근거만 늘린다. UNIQUE 가 NULLS NOT DISTINCT 라
            // clip 범위 태그도 같은 규칙으로 접힌다.
            jdbc.update("""
                    INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at)
                    VALUES (?, ?, ?, ?, now()) ON CONFLICT (clip_id, scene_id, tag_id) DO NOTHING
                    """, taggingId, clip, candidate[0], tagId);
            // 근거 하나가 한 행이다. 워커가 근거를 여러 개 주는 이유는 한 판단이 여러 곳을
            // 보고 나왔기 때문이고, 그것을 하나로 접으면 나머지가 흔적 없이 사라진다.
            @SuppressWarnings("unchecked")
            var evidences = (List<Object[]>) candidate[5];
            for (Object[] evidence : evidences)
                jdbc.update(
                        """
                        INSERT INTO npick.tag_evidence (evidence_id, tagging_id, source, confidence,
                            verification_status, source_ref_type, source_ref_id, created_at)
                        SELECT ?, tagging_id, 'vlm', ?, 'unverified', ?, ?, now() FROM npick.tagging
                        WHERE clip_id=? AND scene_id=? AND tag_id=?
                        """,
                        TsidGenerator.generate(),
                        candidate[4],
                        evidence[0],
                        evidence[1],
                        clip,
                        candidate[0],
                        tagId);
        }
        return Map.of();
    }

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
     * 판단의 시각 근거가 가리키는 {@code keyframe_id}. 텍스트 근거(v2 의 OCR·대사)뿐이면 {@code null} 이다 — {@code tag_evidence} 의 CHECK 가 참조
     * 쌍을 통째로만 요구한다.
     */
    /**
     * 판단의 근거를 {@code tag_evidence} 의 {@code (source_ref_type, source_ref_id)} 쌍으로 푼다.
     *
     * <p>v2 의 근거는 셋이다 — 이미지, OCR 관측, 대사(계약 §4.3.3). <b>OCR 근거도 프레임 참조를 실으므로 {@code keyframe} 으로 되돌린다.</b>
     * {@code observationIndex} 는 payload 배열의 위치이고 DB ID 가 아니어서, 그것을 {@code ocr_observation_id} 로 바꾸려면 이 어댑터의 INSERT
     * 순서에만 기대는 암묵 규약이 하나 더 생긴다. 관측으로 되돌리는 길은 {@code ocr_result} 산출물이 이미 정본이다(§4.3.2).
     */
    private List<Object[]> evidences(
            JsonNode judgement, Map<String, Long> keyframes, List<Map<String, Object>> scenes, boolean mayBeEmpty) {
        var values = array(judgement, "evidence");
        if (values.isEmpty() && !mayBeEmpty) invalid();
        var rows = new ArrayList<Object[]>();
        for (JsonNode evidence : values) {
            // 이미지 근거에는 이 키가 없다. 기존 객체 모양을 유지하기 위해서다(§4.3.3).
            String kind = evidence.has("sourceRefType") ? text(evidence, "sourceRefType") : "keyframe";
            switch (kind) {
                case "keyframe", "ocr_observation" -> {
                    Long keyframe = keyframes.get(integer(evidence, "sceneIndex", 0) + ":"
                            + integer(evidence, "timestampMs", 0) + ":" + text(evidence, "storageKey"));
                    if (keyframe == null) invalid();
                    rows.add(new Object[] {"keyframe", keyframe});
                }
                case "scene" -> {
                    long index = integer(evidence, "sceneIndex", 0);
                    text(evidence, "segmentId");
                    if (index >= scenes.size()) invalid();
                    rows.add(new Object[] {"scene", scenes.get((int) index).get("scene_id")});
                }
                default -> invalid();
            }
        }
        return rows;
    }

    /**
     * 유형을 확인하고 {@code tag.match_value} 를 만든다. 쓰기는 하지 않는다 — 뒤 장면의 위반이 앞 장면의 태그를 남기지 않도록 검증을 저장 앞에서 끝낸다.
     *
     * <p>정규화는 백엔드에 하나뿐인 {@link TagMatchValue} 를 지난다. 구현이 둘이 되면 같은 값이 두 표기로 {@code UNIQUE} 를 통과하고 정확 일치 조회가 조용히 0건이 된다.
     */
    private static String matchValue(String type, String value) {
        TagType tagType;
        try {
            tagType = TagType.from(type);
        } catch (BusinessException unknown) {
            // 워커가 보낸 유형이 어휘 밖이면 태그 오류가 아니라 잘못된 단계 출력이다.
            throw new BusinessException(WorkerIntegrationErrorCode.INVALID_OUTPUT, unknown);
        }
        String match = TagMatchValue.normalize(value);
        // 화면에 날짜가 보인다는 사실과 그것이 방송일·촬영일이라는 판단은 다르다(FRD §3 F-04).
        // 워커 schema 에 그 유형이 없으므로 여기 오는 것은 계약 위반이다.
        //
        // 길이는 `tag.match_value` 의 varchar(255) 다. 워커 schema 의 `value` 에는 상한이
        // 없어, 여기서 막지 않으면 INSERT 가 트랜잭션을 SQL 오류로 끊는다 — 워커가 받는 것은
        // JOB_400_001 이 아니라 500 이고 그 응답은 재시도 가능으로 분류돼 같은 자리를 반복한다.
        if (tagType.date() || match.isBlank() || match.length() > 255) invalid();
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
        var seen = new HashSet<String>();
        for (JsonNode decision : array(decisions, "decisions")) {
            String id = text(decision, "segmentId");
            if (!originals.containsKey(id)
                    || !seen.add(id)
                    || !decision.path("selected").isBoolean()) invalid();
            JsonNode original = originals.get(id);
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
                JsonNode other = originals.get(conflict.asText());
                if (other == null
                        || priority(other) >= priority(original)
                        || Math.max(integer(other, "s", 0), integer(original, "s", 0))
                                >= Math.min(integer(other, "e", 1), integer(original, "e", 1))) invalid();
            }
        }
        if (!seen.equals(originals.keySet()) || !transcript.path("asrRequired").isBoolean()) invalid();
        var ranges = array(transcript, "candidateRanges");
        for (JsonNode range : ranges) if (integer(range, "e", 1) <= integer(range, "s", 0)) invalid();
        String reason = text(transcript, "reasonCode");
        if (!List.of("SUBTITLE_COVERED", "UNCOVERED_RANGES", "NO_VALID_SUBTITLE")
                .contains(reason)) invalid();
        if (transcript.path("asrRequired").booleanValue() != !ranges.isEmpty()
                || reason.equals("SUBTITLE_COVERED") != ranges.isEmpty()) invalid();
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
        return node.path(key).asText();
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
