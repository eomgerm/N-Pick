package com.npick.pipeline.infrastructure.persistence.query;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.port.WorkerArtifactPort;
import com.npick.pipeline.application.query.ProcessingDetailsResult;
import com.npick.pipeline.application.query.ProcessingDetailsResult.Stage;
import com.npick.pipeline.application.query.ProcessingDetailsResult.Transcript;
import com.npick.pipeline.domain.model.PipelineRun;
import com.npick.pipeline.domain.model.PipelineStages;

/** 읽기 전용 allowlist. 상태 전이, 선택 알고리즘, 재시도 정책을 실행하지 않는다. */
public final class ProcessingRecordReader {
    private static final Set<String> STATUSES = Set.of("pending", "running", "succeeded", "failed", "skipped");
    private static final List<String> SOURCES = List.of("uploaded", "embedded", "asr");
    private static final Set<String> REASONS =
            Set.of("SUBTITLE_COVERED", "UNCOVERED_RANGES", "NO_VALID_SUBTITLE", "NO_SPEECH_DETECTED");
    private static final Map<String, String> CHANNELS = Map.of(
            "vlm_metadata",
            "caption",
            "ocr",
            "ocr",
            "asr",
            "asr",
            "scene_transcript_mapping",
            "transcript",
            "entity_extraction",
            "tags",
            "text_embedding",
            "text_embedding");
    private final ObjectMapper mapper;
    private final WorkerArtifactPort artifacts;

    public ProcessingRecordReader(ObjectMapper mapper, WorkerArtifactPort artifacts) {
        this.mapper = mapper;
        this.artifacts = artifacts;
    }

    public ProcessingDetailsResult read(long runId, String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (tools.jackson.core.JacksonException failure) {
            return unavailable(runId, "unavailable");
        }
        if (root == null || !root.isObject() || root.isEmpty()) return unavailable(runId, "unavailable");
        boolean wrapped = root.has("schemaVersion");
        if (wrapped && !root.path("schemaVersion").isString()) return unavailable(runId, "unavailable");
        if (wrapped && !PipelineRun.SCHEMA.equals(root.path("schemaVersion").asString()))
            return unavailable(runId, "unsupported_version");
        JsonNode states = wrapped ? root.path("stages") : root;
        if (!states.isObject()) return unavailable(runId, "unavailable");
        List<Stage> stages = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int known = 0;
        boolean channelsKnown = true;
        for (String name : PipelineStages.NAMES) {
            JsonNode state = states.path(name);
            if (!wrapped && name.equals("scene_detection") && state.isMissingNode())
                state = states.path("scene_detect");
            String status = allowed(state.path("status"), STATUSES);
            if (status != null) known++;
            String error = code(state.path("errorCode"));
            if (error == null) error = code(state.path("error").path("code"));
            if (error == null && !wrapped) error = code(state.path("error_code"));
            String reason = allowed(state.path("reasonCode"), REASONS);
            if (reason == null) reason = allowed(state.path("output").path("reasonCode"), REASONS);
            if ("failed".equals(status) || ("skipped".equals(status) && PipelineStages.FATAL.contains(name)))
                failed.add(name);
            if (CHANNELS.containsKey(name) && status == null) channelsKnown = false;
            if (CHANNELS.containsKey(name) && ("failed".equals(status) || ("skipped".equals(status) && error != null)))
                missing.add(CHANNELS.get(name));
            Boolean automatic = automaticRetryable(state, status);
            Integer maxAttempts = integer(state.path("retryPolicy").path("maxAttempts"));
            if (maxAttempts != null && maxAttempts == 0) maxAttempts = null;
            stages.add(new Stage(
                    name,
                    status == null ? "unknown" : status,
                    integer(state.path("attempts")),
                    instant(state.path("startedAt")),
                    instant(state.path("finishedAt")),
                    error,
                    reason,
                    automatic,
                    maxAttempts,
                    failedAttempts(state.path("failedAttempts"))));
        }
        if (known == 0) return unavailable(runId, "unavailable");
        String availability = known != PipelineStages.NAMES.size() ? "partial" : wrapped ? "available" : "legacy";
        return new ProcessingDetailsResult(
                runId,
                availability,
                List.copyOf(stages),
                List.copyOf(failed),
                channelsKnown ? List.copyOf(missing) : null,
                transcript(runId, states));
    }

    private Transcript transcript(long runId, JsonNode states) {
        JsonNode selection = states.path("transcript_selection");
        JsonNode selectionOutput = selection.path("output").path("transcript");
        boolean selected = "succeeded".equals(allowed(selection.path("status"), STATUSES));
        JsonNode asr = states.path("asr");
        String asrStatus = allowed(asr.path("status"), STATUSES);
        String asrReason = allowed(asr.path("reasonCode"), REASONS);
        if (asrReason == null) asrReason = allowed(asr.path("output").path("reasonCode"), REASONS);
        Integer count = "succeeded".equals(asrStatus)
                        && asr.path("output").path("segments").isArray()
                ? asr.path("output").path("segments").size()
                : null;
        String embedded = allowed(
                selection.path("preparedTranscript").path("embeddedInspection").path("status"),
                Set.of("EXTRACTED", "NO_TRACK", "UNSUPPORTED", "NO_VALID_SEGMENTS", "EXTRACTION_FAILED"));
        // ASR raw segments are candidates, not adopted dialogue. Only the stored decisions snapshot selects sources.
        boolean mapped = "succeeded"
                .equals(allowed(states.path("scene_transcript_mapping").path("status"), STATUSES));
        String selectionStage = mapped ? "scene_transcript_mapping" : selected ? "transcript_selection" : null;
        JsonNode snapshot =
                mapped ? states.path("scene_transcript_mapping").path("output").path("transcript") : selectionOutput;
        Adoption adoption = selectionStage == null ? null : selectedSources(runId, snapshot);
        List<String> sources = adoption == null ? null : adoption.sources();
        String representative = sources == null
                ? null
                : sources.contains("uploaded") || sources.contains("embedded")
                        ? "provided"
                        : sources.contains("asr") ? "asr" : "none";
        return new Transcript(
                sources == null ? "unavailable" : "available",
                selectionStage,
                sources,
                adoption == null ? null : adoption.reasons(),
                representative,
                selected && selectionOutput.path("asrRequired").isBoolean()
                        ? selectionOutput.path("asrRequired").booleanValue()
                        : null,
                selected ? allowed(selectionOutput.path("reasonCode"), REASONS) : null,
                asrStatus == null ? "unknown" : asrStatus,
                asrReason,
                count,
                embedded);
    }

    private record Adoption(List<String> sources, List<String> reasons) {}

    private static Boolean automaticRetryable(JsonNode state, String status) {
        if (status == null) return null;
        if (Set.of("failed", "skipped", "succeeded").contains(status)) return false;
        if (!state.path("retryScheduled").isBoolean()) return null;
        // claim consumes retryPending but retains retryScheduled as the previous completion's decision.
        // Read the outstanding scheduling state, never recompute policy or trust worker errorRetryable.
        if (!"pending".equals(status) || !state.path("retryScheduled").booleanValue()) return false;
        return state.path("retryPending").isBoolean()
                ? state.path("retryPending").booleanValue()
                : null;
    }

    private static List<ProcessingDetailsResult.FailedAttempt> failedAttempts(JsonNode values) {
        if (!values.isArray()) return null;
        List<ProcessingDetailsResult.FailedAttempt> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject()) return null;
            result.add(new ProcessingDetailsResult.FailedAttempt(
                    integer(value.path("attempt")),
                    code(value.path("error").path("code")),
                    instant(value.path("finishedAt"))));
        }
        return List.copyOf(result);
    }

    private Adoption selectedSources(long runId, JsonNode snapshot) {
        try {
            JsonNode segmentsRef = snapshot.path("segmentsArtifact");
            JsonNode originals = document(runId, segmentsRef, "transcript_segments");
            JsonNode decisions = document(runId, snapshot.path("decisionsArtifact"), "transcript_decisions");
            if (!"npick.transcript.segments/v1"
                            .equals(originals.path("schemaVersion").asString())
                    || !"npick.transcript.decisions/v1"
                            .equals(decisions.path("schemaVersion").asString())
                    || !segmentsRef.equals(decisions.path("segmentsArtifact"))
                    || !originals.path("segments").isArray()
                    || !decisions.path("decisions").isArray()) return null;
            Map<String, String> sourceById = new HashMap<>();
            for (JsonNode segment : originals.path("segments")) {
                String id = segment.path("segmentId").asString("");
                String source = allowed(segment.path("sourceDetail"), Set.copyOf(SOURCES));
                if (id.isBlank() || source == null || sourceById.putIfAbsent(id, source) != null) return null;
            }
            Set<String> seen = new HashSet<>();
            Set<String> adopted = new HashSet<>();
            Set<String> reasons = new HashSet<>();
            for (JsonNode decision : decisions.path("decisions")) {
                String id = decision.path("segmentId").asString("");
                if (!sourceById.containsKey(id)
                        || !seen.add(id)
                        || !decision.path("selected").isBoolean()) return null;
                if (decision.path("selected").booleanValue()) {
                    String expectedReason = "asr".equals(sourceById.get(id)) ? "ASR_SUPPLEMENT" : "PREFERRED_SUBTITLE";
                    if (!expectedReason.equals(decision.path("reasonCode").asString())) return null;
                    adopted.add(sourceById.get(id));
                    reasons.add(expectedReason);
                } else if (!"OVERLAPS_HIGHER_PRIORITY"
                        .equals(decision.path("reasonCode").asString())) return null;
            }
            return seen.equals(sourceById.keySet())
                    ? new Adoption(
                            SOURCES.stream().filter(adopted::contains).toList(),
                            List.of("PREFERRED_SUBTITLE", "ASR_SUPPLEMENT").stream()
                                    .filter(reasons::contains)
                                    .toList())
                    : null;
        } catch (BusinessException | IllegalArgumentException | tools.jackson.core.JacksonException failure) {
            // Inaccessible/corrupt legacy artifacts do not turn missing evidence into a successful empty selection.
            return null;
        }
    }

    private JsonNode document(long runId, JsonNode node, String kind) {
        String key = node.path("storageKey").asString("");
        long size = node.path("byteSize").asLong(-1);
        if (!kind.equals(node.path("kind").asString())
                || !key.startsWith("runs/" + runId + "/")
                || !node.path("byteSize").isIntegralNumber()
                || size < 0
                || size > 16 * 1024 * 1024) throw new IllegalArgumentException("unavailable artifact");
        JsonNode document = mapper.readTree(artifacts.verified(new WorkerArtifactPort.Ref(
                kind, key, size, node.path("contentHash").asString(""))));
        if (document == null || !document.isObject()) throw new IllegalArgumentException("unavailable artifact");
        return document;
    }

    private static ProcessingDetailsResult unavailable(long runId, String status) {
        return new ProcessingDetailsResult(runId, status, List.of(), null, null, null);
    }

    private static String allowed(JsonNode node, Set<String> values) {
        return node.isString() && values.contains(node.asString()) ? node.asString() : null;
    }

    private static String code(JsonNode node) {
        return node.isString() && node.asString().matches("[A-Za-z][A-Za-z0-9_-]{0,63}") ? node.asString() : null;
    }

    private static Integer integer(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0 ? node.intValue() : null;
    }

    private static Instant instant(JsonNode node) {
        if (!node.isString()) return null;
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException failure) {
            return null;
        }
    }
}
