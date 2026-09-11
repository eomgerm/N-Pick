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

    @Override
    public boolean supports(String stage) {
        return java.util.Set.of("scene_detection", "frame_extraction", "transcript_selection", "asr")
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
