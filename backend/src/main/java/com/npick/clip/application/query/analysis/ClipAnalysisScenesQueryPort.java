package com.npick.clip.application.query.analysis;

import java.util.List;
import java.util.Optional;

public interface ClipAnalysisScenesQueryPort {

    Optional<RunScope> findRunScope(long clipId, long pipelineRunId);

    List<SceneCoverage> findCoverage(long clipId, long pipelineRunId);

    List<SceneRow> findPage(long clipId, long pipelineRunId, int offset, int size);

    record RunScope(boolean searchApplied) {}

    record SceneCoverage(long sceneId, boolean captioned, boolean transcripted, boolean embedded) {}

    record SceneRow(
            long sceneId,
            int sceneIndex,
            long startTimeMs,
            long endTimeMs,
            Long representativeFrameTimestampMs,
            String caption,
            String shotType,
            String transcriptText,
            String transcriptSource,
            boolean embeddingReady,
            List<String> ocrTexts) {}
}
