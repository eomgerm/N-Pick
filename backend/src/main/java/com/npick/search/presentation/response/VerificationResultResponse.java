package com.npick.search.presentation.response;

import java.util.List;
import java.util.Map;

import com.npick.search.application.query.search.VerificationResult;

/** 검증 재검색 응답 (web-api §6.4, S15P21A501-83 Task 6). snake_case, {@code *_id} 는 십진 문자열이다. */
public record VerificationResultResponse(
        String execution_id,
        List<EnteredScene> entered_scenes,
        List<DroppedScene> dropped_scenes,
        List<String> verification_rule_set) {

    public record EnteredScene(String scene_id, Map<String, Object> reason) {}

    public record DroppedScene(String scene_id, String reason) {}

    public static VerificationResultResponse of(VerificationResult r) {
        return new VerificationResultResponse(
                Long.toString(r.executionId()),
                r.entered().stream()
                        .map(e -> new EnteredScene(Long.toString(e.sceneId()), e.reason()))
                        .toList(),
                r.dropped().stream()
                        .map(d -> new DroppedScene(Long.toString(d.sceneId()), d.reason()))
                        .toList(),
                r.verificationRuleSet().stream().map(String::valueOf).toList());
    }
}
