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
import com.npick.pipeline.infrastructure.artifact.LocalWorkerArtifactAdapter;

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
                        "transcript_selection",
                        "asr",
                        "scene_transcript_mapping",
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
                || !text(body.path("versions"), "outputSchemaVersion").equals("npick.stage." + stage + ".output/v1"))
            invalid();
        var refs = references(body.path("artifacts"), prefix);
        JsonNode output = body.path("output");
        if (!output.isObject() || output.isEmpty()) invalid();
        return switch (stage) {
            case "scene_detection" -> scenes(runId, clipId, output);
            case "frame_extraction" -> keyframes(runId, output, refs);
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
