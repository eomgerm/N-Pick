package com.npick.pipeline.domain.model;

import java.util.List;
import java.util.Set;

/** ai/src/npick_worker/stages.py의 BE 전사. 계약 테스트로 순서와 fatal을 대조한다. */
public final class PipelineStages {
    public static final List<String> NAMES = List.of(
            "scene_detection",
            "frame_extraction",
            "vlm_metadata",
            "ocr",
            "transcript_selection",
            "asr",
            "scene_transcript_mapping",
            "entity_extraction",
            "text_embedding",
            "indexing");
    public static final Set<String> FATAL = Set.of("scene_detection", "frame_extraction", "indexing");

    private PipelineStages() {}

    public static String outputSchema(String stage) {
        return "npick.stage." + stage + ".output/v1";
    }
}
