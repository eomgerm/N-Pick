package com.npick.feedback.presentation.response;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.npick.feedback.application.query.CorrectionCandidates;

/**
 * 대기 중인 교정 후보 복원 응답 (S15P21A501-317).
 *
 * <p>id 는 전부 십진 문자열이다 — TSID(~4.6e17)는 JavaScript 안전 정수(9.0e15)를 넘는다 (web-api.md §2.3·§8).
 * {@code condition}·{@code patch} 는 생성 요청({@code POST …/parse-patch-candidate})에 보낸 것과 같은 JSON 객체로 되돌려 FE 가 그대로 다시 쓸 수
 * 있게 한다.
 */
public record CorrectionCandidatesResponse(
        List<TagCandidateResponse> tags,
        List<ParsePatchCandidateResponse> parsePatches,
        List<SceneExcludeCandidateResponse> sceneExcludes) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public record TagCandidateResponse(
            String evidenceId,
            String taggingId,
            String action,
            String scope,
            String tagType,
            String matchValue,
            String displayName) {}

    public record ParsePatchCandidateResponse(
            String searchRuleId, JsonNode condition, JsonNode patch, String replacesRuleId) {}

    public record SceneExcludeCandidateResponse(String searchRuleId, String targetSceneId) {}

    public static CorrectionCandidatesResponse from(CorrectionCandidates candidates) {
        return new CorrectionCandidatesResponse(
                candidates.tags().stream()
                        .map(tag -> new TagCandidateResponse(
                                String.valueOf(tag.evidenceId()),
                                String.valueOf(tag.taggingId()),
                                tag.action(),
                                tag.scope(),
                                tag.tagType(),
                                tag.matchValue(),
                                tag.displayName()))
                        .toList(),
                candidates.parsePatches().stream()
                        .map(patch -> new ParsePatchCandidateResponse(
                                String.valueOf(patch.searchRuleId()),
                                OBJECT_MAPPER.readTree(patch.conditionJson()),
                                OBJECT_MAPPER.readTree(patch.patchJson()),
                                patch.replacesRuleId() == null ? null : String.valueOf(patch.replacesRuleId())))
                        .toList(),
                candidates.sceneExcludes().stream()
                        .map(exclude -> new SceneExcludeCandidateResponse(
                                String.valueOf(exclude.searchRuleId()), String.valueOf(exclude.targetSceneId())))
                        .toList());
    }
}
