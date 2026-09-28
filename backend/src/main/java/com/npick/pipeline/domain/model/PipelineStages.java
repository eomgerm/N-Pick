package com.npick.pipeline.domain.model;

import java.util.List;
import java.util.Set;

/** ai/src/npick_worker/stages.py의 BE 전사. 계약 테스트로 순서와 fatal을 대조한다. */
public final class PipelineStages {
    public static final List<String> NAMES = List.of(
            "scene_detection",
            "frame_extraction",
            "ocr",
            "transcript_selection",
            "asr",
            "scene_transcript_mapping",
            "vlm_metadata",
            "entity_extraction",
            "text_embedding",
            "indexing");
    public static final Set<String> FATAL = Set.of("scene_detection", "frame_extraction", "indexing");

    private PipelineStages() {}

    /** 출력 payload 의 schema 문자열. 워커의 {@code jobs/versions.py} 와 같은 예외 목록을 가진다. */
    public static String outputSchema(String stage) {
        return "npick.stage." + stage + (V2_OUTPUT.contains(stage) ? ".output/v2" : ".output/v1");
    }

    /** {@code ocr} 은 병합 그룹(S15P21A501-95), {@code vlm_metadata} 는 텍스트 근거(S15P21A501-92)로 봉투가 올라갔다. */
    private static final Set<String> V2_OUTPUT = Set.of("ocr", "vlm_metadata");
}
