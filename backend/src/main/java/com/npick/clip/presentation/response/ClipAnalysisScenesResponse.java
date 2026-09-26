package com.npick.clip.presentation.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.clip.application.query.analysis.ClipAnalysisScenesResult;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClipAnalysisScenesResponse(
        @JsonProperty("clip_id") String clipId,
        @JsonProperty("pipeline_run_id") String pipelineRunId,
        @JsonProperty("search_applied") boolean searchApplied,
        SummaryResponse summary,
        List<SceneResponse> items,
        int page,
        int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") long totalPages,
        @JsonProperty("has_next") boolean hasNext) {

    public static ClipAnalysisScenesResponse from(ClipAnalysisScenesResult result) {
        return new ClipAnalysisScenesResponse(
                Long.toString(result.clipId()),
                Long.toString(result.pipelineRunId()),
                result.searchApplied(),
                SummaryResponse.from(result.summary()),
                result.items().stream().map(SceneResponse::from).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.hasNext());
    }

    public record SummaryResponse(
            @JsonProperty("total_scenes") long totalScenes,
            @JsonProperty("captioned_scenes") long captionedScenes,
            @JsonProperty("transcript_scenes") long transcriptScenes,
            @JsonProperty("tagged_scenes") long taggedScenes,
            @JsonProperty("embedded_scenes") long embeddedScenes) {
        static SummaryResponse from(ClipAnalysisScenesResult.Summary summary) {
            return new SummaryResponse(
                    summary.totalScenes(),
                    summary.captionedScenes(),
                    summary.transcriptScenes(),
                    summary.taggedScenes(),
                    summary.embeddedScenes());
        }
    }

    public record SceneResponse(
            @JsonProperty("scene_id") String sceneId,
            @JsonProperty("scene_index") int sceneIndex,
            @JsonProperty("start_time_ms") long startTimeMs,
            @JsonProperty("end_time_ms") long endTimeMs,

            @JsonProperty("representative_frame_timestamp_ms")
            Long representativeFrameTimestampMs,

            String caption,
            @JsonProperty("shot_type") String shotType,
            TranscriptResponse transcript,
            List<TagResponse> tags,
            @JsonProperty("embedding_ready") boolean embeddingReady,
            @JsonProperty("ocr_texts") List<String> ocrTexts) {
        static SceneResponse from(ClipAnalysisScenesResult.Scene scene) {
            return new SceneResponse(
                    Long.toString(scene.sceneId()),
                    scene.sceneIndex(),
                    scene.startTimeMs(),
                    scene.endTimeMs(),
                    scene.representativeFrameTimestampMs(),
                    scene.caption(),
                    scene.shotType(),
                    TranscriptResponse.from(scene.transcript()),
                    scene.tags().stream().map(TagResponse::from).toList(),
                    scene.embeddingReady(),
                    scene.ocrTexts());
        }
    }

    public record TranscriptResponse(String text, String source) {
        static TranscriptResponse from(ClipAnalysisScenesResult.Transcript transcript) {
            return transcript == null ? null : new TranscriptResponse(transcript.text(), transcript.source());
        }
    }

    public record TagResponse(
            @JsonProperty("tag_id") String tagId,
            String type,
            String name,
            @JsonProperty("match_value") String matchValue,
            String scope,
            String source,
            String verification) {
        static TagResponse from(ClipAnalysisScenesResult.Tag tag) {
            return new TagResponse(
                    Long.toString(tag.tagId()),
                    tag.type(),
                    tag.name(),
                    tag.matchValue(),
                    tag.scope(),
                    tag.source(),
                    tag.verification());
        }
    }
}
