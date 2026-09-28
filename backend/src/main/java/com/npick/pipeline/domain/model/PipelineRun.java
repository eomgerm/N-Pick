package com.npick.pipeline.domain.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.domain.error.PipelineErrorCode;

/** 한 행을 잠근 트랜잭션 안에서만 변경한다. */
public final class PipelineRun {
    public static final String SCHEMA = "npick.stage_states/v1";
    private final long id;
    private final long clipId;
    private final int processingNo;
    private final String pipelineVersion;
    private final Map<String, Object> envelope;
    private final Map<String, Map<String, Object>> stages = new LinkedHashMap<>();
    private String status;
    private String errorCode;
    private Instant startedAt;
    private Instant finishedAt;
    private UUID leaseId;
    private String leaseStage;
    private String workerId;
    private Instant leaseExpiresAt;
    private Instant heartbeatAt;

    public PipelineRun(Snapshot snapshot) {
        id = snapshot.id();
        clipId = snapshot.clipId();
        processingNo = snapshot.processingNo();
        pipelineVersion = snapshot.pipelineVersion();
        status = snapshot.status();
        errorCode = snapshot.errorCode();
        startedAt = snapshot.startedAt();
        finishedAt = snapshot.finishedAt();
        leaseId = snapshot.leaseId();
        leaseStage = snapshot.leaseStage();
        workerId = snapshot.workerId();
        leaseExpiresAt = snapshot.leaseExpiresAt();
        heartbeatAt = snapshot.heartbeatAt();
        Map<String, Object> original = JsonValues.copy(snapshot.stageStates());
        boolean wrapped = original.containsKey("schemaVersion");
        if (wrapped && !SCHEMA.equals(original.get("schemaVersion"))) fail(PipelineErrorCode.INVALID_STATE);
        envelope = wrapped ? new LinkedHashMap<>(original) : new LinkedHashMap<>();
        Map<String, Object> stored = wrapped ? JsonValues.object(original.get("stages")) : original;
        if (!stored.keySet().equals(new java.util.HashSet<>(PipelineStages.NAMES)))
            fail(PipelineErrorCode.INVALID_STATE);
        for (String name : PipelineStages.NAMES) {
            Map<String, Object> state = new LinkedHashMap<>(JsonValues.object(stored.get(name)));
            if (!(state.get("status") instanceof String)
                    || !java.util.Set.of("pending", "running", "succeeded", "failed", "skipped")
                            .contains(state.get("status"))
                    || !(state.get("attempts") instanceof Number n)
                    || n.intValue() < 0
                    || n.doubleValue() != n.intValue()) fail(PipelineErrorCode.INVALID_STATE);
            stages.put(name, state);
        }
    }

    public void bindExpectedVersions(String version, Map<String, String> expected) {
        if (!pipelineVersion.equals(version)) fail(PipelineErrorCode.VERSION_CONFLICT);
        for (String stage : PipelineStages.NAMES) {
            Object previous = stages.get(stage).get("expectedStageVersion");
            if (previous != null && !previous.equals(expected.get(stage))) fail(PipelineErrorCode.VERSION_CONFLICT);
            stages.get(stage).put("expectedStageVersion", expected.get(stage));
        }
    }

    public String nextStage() {
        if (!status.equals("queued") && !status.equals("running")) return null;
        if (leaseId != null) return null;
        for (String stage : PipelineStages.NAMES) {
            String current = (String) stages.get(stage).get("status");
            if ("running".equals(current)
                    || (PipelineStages.FATAL.contains(stage)
                            && ("failed".equals(current) || "skipped".equals(current))))
                fail(PipelineErrorCode.INVALID_STATE);
            if ("pending".equals(current)) return stage;
        }
        return null;
    }

    public void claim(String stage, String worker, UUID lease, Map<String, Object> device, Instant now) {
        claim(stage, worker, lease, device, now, StageRetrySettings.disabled());
    }

    public void claim(
            String stage,
            String worker,
            UUID lease,
            Map<String, Object> device,
            Instant now,
            StageRetrySettings configured) {
        if (!Objects.equals(nextStage(), stage)) fail(PipelineErrorCode.INVALID_STATE);
        Map<String, Object> state = stages.get(stage);
        preserveLegacyCompletion(state);
        bindRetryPolicy(stage, configured);
        // lease 회수는 실행 실패가 아니므로 같은 attempt를 다시 배정한다.
        int attempt = Math.max(1, ((Number) state.get("attempts")).intValue());
        if (Boolean.TRUE.equals(state.remove("retryPending"))) attempt = Math.addExact(attempt, 1);
        state.put("status", "running");
        state.put("attempts", attempt);
        state.put("leaseId", lease.toString());
        state.put("workerId", worker);
        state.put("device", JsonValues.copy(device));
        state.put("startedAt", now.toString());
        state.remove("finishedAt");
        state.remove("preparedTranscript");
        state.remove("inputArtifacts");
        status = "running";
        if (startedAt == null) startedAt = now;
        leaseId = lease;
        leaseStage = stage;
        workerId = worker;
        heartbeatAt = now;
        leaseExpiresAt = now.plusSeconds(60);
    }

    public void fence(String stage, UUID lease, String worker, Instant now) {
        if (leaseId == null
                || !leaseId.equals(lease)
                || !Objects.equals(stage, leaseStage)
                || !Objects.equals(workerId, worker)
                || !now.isBefore(leaseExpiresAt)) fail(PipelineErrorCode.STALE_LEASE);
    }

    public void heartbeat(String stage, UUID lease, String worker, Instant now) {
        fence(stage, lease, worker, now);
        heartbeatAt = now;
        leaseExpiresAt = now.plusSeconds(60);
    }

    public void attachTranscript(Map<String, Object> transcript, java.util.List<Map<String, Object>> artifacts) {
        if (!"transcript_selection".equals(leaseStage) || !"running".equals(status))
            fail(PipelineErrorCode.INVALID_STATE);
        var stage = stages.get(leaseStage);
        if (stage.containsKey("preparedTranscript")) fail(PipelineErrorCode.INVALID_STATE);
        stage.put("preparedTranscript", JsonValues.copy(transcript));
        stage.put("inputArtifacts", JsonValues.copy(Map.of("items", artifacts)).get("items"));
    }

    public boolean reclaim(Instant now) {
        if (leaseId == null || !now.isAfter(leaseExpiresAt.plusSeconds(15))) return false;
        Map<String, Object> state = stages.get(leaseStage);
        state.put("status", "pending");
        state.put("leaseId", null);
        clearLease();
        return true;
    }

    public Map<String, Object> duplicate(String stage, UUID lease, String worker, String key, String hash) {
        Map<String, Object> state = stages.get(stage);
        if (state == null) fail(PipelineErrorCode.INVALID_RESULT);
        preserveLegacyCompletion(state);
        var accepted =
                JsonValues.object(JsonValues.object(state.get("completions")).get(key));
        if (accepted.isEmpty()) return null;
        // 완료된 동일 lease의 재전송만 허용한다. 회수된 이전 lease는 같은 attempt라도 거절한다.
        if (!lease.toString().equals(accepted.get("leaseId")) || !worker.equals(accepted.get("workerId")))
            fail(PipelineErrorCode.STALE_LEASE);
        if (!hash.equals(accepted.get("requestSha256"))) fail(PipelineErrorCode.IDEMPOTENCY_CONFLICT);
        return JsonValues.copy(JsonValues.object(accepted.get("response")));
    }

    public void complete(String stage, Map<String, Object> result, String hash, Instant now) {
        complete(stage, result, hash, now, StageRetrySettings.disabled());
    }

    public void complete(
            String stage, Map<String, Object> result, String hash, Instant now, StageRetrySettings retries) {
        if (!java.util.List.of("succeeded", "failed", "skipped").contains(result.get("status")))
            fail(PipelineErrorCode.INVALID_STATE);
        Map<String, Object> state = stages.get(stage);
        if (!"running".equals(state.get("status")) || !Objects.equals(stage, leaseStage))
            fail(PipelineErrorCode.INVALID_STATE);
        if (!idempotencyKey(stage).equals(result.get("idempotencyKey"))
                || !(result.get("attempt") instanceof Number attempt)
                || attempt.intValue() != ((Number) state.get("attempts")).intValue())
            fail(PipelineErrorCode.INVALID_RESULT);
        Map<String, Object> versions = JsonValues.object(result.get("versions"));
        for (String key : java.util.List.of(
                "status",
                "startedAt",
                "finishedAt",
                "durationMs",
                "versions",
                "metrics",
                "output",
                "artifacts",
                "warnings",
                "error")) {
            state.put(key, result.get(key));
        }
        state.remove("envelopeVersion");
        state.remove("idempotencyKey");
        state.remove("attempt");
        state.remove("stage");
        state.put("leaseId", null);
        state.put("completedLeaseId", leaseId.toString());
        state.put("completedWorkerId", workerId);
        state.put("lastIdempotencyKey", idempotencyKey(stage));
        state.put("lastRequestSha256", hash);
        state.put(
                "versionMismatch",
                Objects.equals(versions.get("stageVersion"), state.get("expectedStageVersion"))
                                || "unknown".equals(versions.get("stageVersion"))
                        ? null
                        : true);
        Map<String, Object> error = JsonValues.object(result.get("error"));
        state.put("errorCode", error.get("code"));
        state.put("errorRetryable", error.get("retryable"));
        state.put("errorMessage", error.get("message"));
        clearLease();
        bindRetryPolicy(stage, retries);
        boolean retry = "failed".equals(state.get("status"))
                && retryPolicy(stage).permits(stage, ((Number) state.get("attempts")).intValue(), error);
        state.put("retryScheduled", retry);
        if (retry) {
            var failures = new java.util.ArrayList<Object>(
                    state.get("failedAttempts") instanceof java.util.List<?> previous ? previous : java.util.List.of());
            failures.add(JsonValues.copy(Map.of(
                    "attempt",
                    state.get("attempts"),
                    "error",
                    error,
                    "finishedAt",
                    state.get("finishedAt"),
                    "idempotencyKey",
                    state.get("lastIdempotencyKey"))));
            state.put("failedAttempts", failures);
            state.put("status", "pending");
            state.put("retryPending", true);
            return;
        }
        if (!"succeeded".equals(state.get("status")) && PipelineStages.FATAL.contains(stage)) {
            finish("failed", Objects.toString(error.get("code"), "STAGE_FAILED"), now);
        }
        if ("transcript_selection".equals(stage) && "succeeded".equals(state.get("status"))) {
            Map<String, Object> transcript =
                    JsonValues.object(JsonValues.object(state.get("output")).get("transcript"));
            if (Boolean.FALSE.equals(transcript.get("asrRequired"))) {
                Map<String, Object> asr = stages.get("asr");
                asr.put("status", "skipped");
                asr.put("attempts", 0);
                asr.put("reasonCode", transcript.get("reasonCode"));
                asr.put("finishedAt", now.toString());
            }
        }
    }

    public void finishIndexing(boolean ready, Instant now) {
        if (!"succeeded".equals(stages.get("indexing").get("status")) || !status.equals("running"))
            fail(PipelineErrorCode.INVALID_STATE);
        if (stages.values().stream().anyMatch(s -> Boolean.TRUE.equals(s.get("versionMismatch"))))
            finish("failed", "PIPELINE_VERSION_MISMATCH", now);
        else finish(ready ? "succeeded" : "failed", ready ? null : "INDEX_FAILED", now);
    }

    public void recordAssignedIds(String stage, Map<String, Object> ids) {
        stages.get(stage).put("assignedIds", JsonValues.copy(ids));
    }

    public void rememberCompletion(String stage, Map<String, Object> response) {
        stages.get(stage).put("completion", JsonValues.copy(response));
        preserveLegacyCompletion(stages.get(stage));
    }

    private static void preserveLegacyCompletion(Map<String, Object> state) {
        if (!(state.get("lastIdempotencyKey") instanceof String key) || state.get("completion") == null) return;
        var completions = new LinkedHashMap<>(JsonValues.object(state.get("completions")));
        completions.putIfAbsent(
                key,
                JsonValues.copy(Map.of(
                        "leaseId", state.get("completedLeaseId"),
                        "workerId", state.getOrDefault("completedWorkerId", state.get("workerId")),
                        "requestSha256", state.get("lastRequestSha256"),
                        "response", state.get("completion"))));
        state.put("completions", completions);
    }

    private void bindRetryPolicy(String stage, StageRetrySettings configured) {
        var state = stages.get(stage);
        if (state.containsKey("retryPolicy")) return;
        int attempts = ((Number) state.get("attempts")).intValue();
        // 정책 기록 전 시작된 구 run은 배정 당시 기본 계약(추가 재시도 없음)을 유지한다.
        // 이미 예약된 재시도가 있다면 그 1회까지만 보존한다.
        int budget = attempts == 0
                ? configured.attemptsFor(stage)
                : Math.addExact(attempts, Boolean.TRUE.equals(state.get("retryPending")) ? 1 : 0);
        state.put(
                "retryPolicy",
                Map.of(
                        "maxAttempts",
                        Math.max(1, budget),
                        "transientErrors",
                        attempts == 0
                                ? configured.transientErrors().stream().sorted().toList()
                                : java.util.List.of()));
    }

    private StageRetrySettings retryPolicy(String stage) {
        var policy = JsonValues.object(stages.get(stage).get("retryPolicy"));
        var codes = new java.util.HashSet<String>();
        if (!(policy.get("maxAttempts") instanceof Number max)
                || max.intValue() < 1
                || max.doubleValue() != max.intValue()
                || !(policy.get("transientErrors") instanceof java.util.List<?>)) fail(PipelineErrorCode.INVALID_STATE);
        for (Object value : (java.util.List<?>) policy.get("transientErrors")) {
            if (!(value instanceof String)) fail(PipelineErrorCode.INVALID_STATE);
            codes.add((String) value);
        }
        return new StageRetrySettings(Map.of(stage, ((Number) policy.get("maxAttempts")).intValue()), codes);
    }

    public int maxAttempts(String stage) {
        bindRetryPolicy(stage, StageRetrySettings.disabled());
        return retryPolicy(stage).attemptsFor(stage);
    }

    public boolean versionsMatch() {
        return stages.values().stream().noneMatch(s -> Boolean.TRUE.equals(s.get("versionMismatch")));
    }

    public Map<String, String> expectedVersions() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String stage : PipelineStages.NAMES) {
            if (!(stages.get(stage).get("expectedStageVersion") instanceof String version)) return Map.of();
            result.put(stage, version);
        }
        return Map.copyOf(result);
    }

    public String idempotencyKey(String stage) {
        return id + ":" + stage + ":" + stages.get(stage).get("attempts");
    }

    public String outputKeyPrefix(String stage) {
        return "runs/" + id + "/" + stage + "/a" + stages.get(stage).get("attempts") + "/";
    }

    public Map<String, Object> state(String stage) {
        return JsonValues.copy(stages.get(stage));
    }

    public Map<String, Object> upstream() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : PipelineStages.NAMES) {
            Map<String, Object> state = stages.get(name);
            if ("succeeded".equals(state.get("status"))) {
                Map<String, Object> output = JsonValues.object(state.get("output"));
                result.put(name, output);
                if (output.containsKey("transcript")) result.put("transcript", output.get("transcript"));
                if (state.containsKey("assignedIds")) result.put(name + "AssignedIds", state.get("assignedIds"));
            }
        }
        return JsonValues.copy(result);
    }

    /** 식별자만 필요한 곳을 위한 접근자. {@link #snapshot()} 은 stage_states_json 전체를 재귀 복사한다. */
    public long clipId() {
        return clipId;
    }

    public Snapshot snapshot() {
        Map<String, Object> root = new LinkedHashMap<>(envelope);
        root.put("schemaVersion", SCHEMA);
        root.put("stages", new LinkedHashMap<>(stages));
        return new Snapshot(
                id,
                clipId,
                processingNo,
                pipelineVersion,
                status,
                errorCode,
                startedAt,
                finishedAt,
                leaseId,
                leaseStage,
                workerId,
                leaseExpiresAt,
                heartbeatAt,
                JsonValues.copy(root));
    }

    private void finish(String value, String error, Instant now) {
        status = value;
        errorCode = error;
        finishedAt = now;
    }

    private void clearLease() {
        leaseId = null;
        leaseStage = null;
        workerId = null;
        leaseExpiresAt = null;
        heartbeatAt = null;
    }

    private static void fail(PipelineErrorCode code) {
        throw new BusinessException(code);
    }

    public record Snapshot(
            long id,
            long clipId,
            int processingNo,
            String pipelineVersion,
            String status,
            String errorCode,
            Instant startedAt,
            Instant finishedAt,
            UUID leaseId,
            String leaseStage,
            String workerId,
            Instant leaseExpiresAt,
            Instant heartbeatAt,
            Map<String, Object> stageStates) {}
}
