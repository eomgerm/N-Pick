package com.npick.clip.application.query.analysis;

import java.util.List;

public record ClipAnalysisScenesResult(
        long clipId,
        long pipelineRunId,
        boolean searchApplied,
        Summary summary,
        List<Scene> items,
        int page,
        int size,
        long totalElements) {

    public long totalPages() {
        return totalElements == 0 ? 0 : (totalElements + size - 1) / size;
    }

    public boolean hasNext() {
        return (long) (page + 1) * size < totalElements;
    }

    public record Summary(
            long totalScenes, long captionedScenes, long transcriptScenes, long taggedScenes, long embeddedScenes) {}

    public record Scene(
            long sceneId,
            int sceneIndex,
            long startTimeMs,
            long endTimeMs,
            Long representativeFrameTimestampMs,
            String caption,
            String shotType,
            Transcript transcript,
            List<Tag> tags,
            boolean embeddingReady,
            List<String> ocrTexts) {}

    public record Transcript(String text, String source) {}

    public record Tag(
            long tagId,
            String type,
            String name,
            String matchValue,
            String scope,
            String source,
            String verification) {}
}
