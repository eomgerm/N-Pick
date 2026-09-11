package com.npick.pipeline.domain.model;

import java.util.Map;
import java.util.Set;

/** 실행 예산과 오류 계약의 교집합. 워커 신고나 설정으로 영구 오류를 일시 오류로 바꾸지 않는다. */
public record StageRetrySettings(Map<String, Integer> maxAttempts, Set<String> transientErrors) {
    public static final Set<String> TRANSIENT_CODES = Set.of(
            "SCENE_DETECTION_FAILED",
            "OCR_FAILED",
            "ASR_FAILED",
            "INDEX_FAILED",
            "MODEL_UNAVAILABLE",
            "OUT_OF_MEMORY",
            "STAGE_TIMEOUT",
            "MEDIA_UNAVAILABLE",
            "ARTIFACT_UPLOAD_FAILED",
            "WORKER_ABORTED",
            "STAGE_FAILED");

    public StageRetrySettings {
        maxAttempts = Map.copyOf(maxAttempts);
        transientErrors = Set.copyOf(transientErrors);
        if (!PipelineStages.NAMES.containsAll(maxAttempts.keySet())
                || maxAttempts.values().stream().anyMatch(value -> value < 1))
            throw new IllegalArgumentException("Invalid stage retry budget");
    }

    public static StageRetrySettings disabled() {
        return new StageRetrySettings(Map.of(), Set.of());
    }

    public int attemptsFor(String stage) {
        return maxAttempts.getOrDefault(stage, 1);
    }

    public boolean permits(String stage, int attempt, Map<String, Object> error) {
        return attempt < attemptsFor(stage)
                && Boolean.TRUE.equals(error.get("retryable"))
                && error.get("code") instanceof String code
                && TRANSIENT_CODES.contains(code)
                && transientErrors.contains(code);
    }
}
