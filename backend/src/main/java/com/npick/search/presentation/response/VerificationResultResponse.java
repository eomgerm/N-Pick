package com.npick.search.presentation.response;

import java.util.List;
import java.util.Map;

import com.npick.search.application.query.search.SceneDiff;
import com.npick.search.application.query.search.VerificationResult;
import com.npick.search.application.query.search.VerificationScene;

/** 검증 재검색 응답 (web-api §6.4, S15P21A501-83 Task 6). snake_case, {@code *_id} 는 십진 문자열이다. */
public record VerificationResultResponse(
        String execution_id,
        List<EnteredScene> entered_scenes,
        List<DroppedScene> dropped_scenes,
        List<String> verification_rule_set) {

    public record EnteredScene(
            String scene_id,
            String clip_id,
            String display_name,
            String scene_description,
            long start_time_ms,
            long end_time_ms,
            Map<String, Object> reason) {}

    public record DroppedScene(
            String scene_id,
            String clip_id,
            String display_name,
            String scene_description,
            long start_time_ms,
            long end_time_ms,
            String reason) {}

    public static VerificationResultResponse of(VerificationResult r) {
        return new VerificationResultResponse(
                Long.toString(r.executionId()),
                r.entered().stream()
                        .map(e -> entered(e, r.scenes().get(e.sceneId())))
                        .toList(),
                r.dropped().stream()
                        .map(d -> dropped(d, r.scenes().get(d.sceneId())))
                        .toList(),
                r.verificationRuleSet().stream().map(String::valueOf).toList());
    }

    private static EnteredScene entered(SceneDiff.Entered entered, VerificationScene scene) {
        return new EnteredScene(
                Long.toString(entered.sceneId()),
                Long.toString(scene.clipId()),
                scene.clipTitle(),
                scene.sceneDescription(),
                scene.startTimeMs(),
                scene.endTimeMs(),
                entered.reason());
    }

    private static DroppedScene dropped(SceneDiff.Dropped dropped, VerificationScene scene) {
        return new DroppedScene(
                Long.toString(dropped.sceneId()),
                Long.toString(scene.clipId()),
                scene.clipTitle(),
                scene.sceneDescription(),
                scene.startTimeMs(),
                scene.endTimeMs(),
                dropped.reason());
    }
}
