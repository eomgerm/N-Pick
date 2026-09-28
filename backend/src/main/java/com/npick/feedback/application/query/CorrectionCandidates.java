package com.npick.feedback.application.query;

import java.util.List;

/**
 * 이 신고에 쌓인 확정 전 교정 후보 전체 (S15P21A501-317). 새로고침 뒤 검수자의 작성 중 교정(태그·해석·장면 제외)을 복원하는 데 쓴다.
 *
 * @param tags 대기 중인 태그 교정 근거 ({@code tag_evidence.confirmed=false})
 * @param parsePatches 대기 중인 해석 교정 규칙 ({@code search_rule.active=false}, patch_parse)
 * @param sceneExcludes 대기 중인 장면 제외 규칙 ({@code search_rule.active=false}, exclude_scene)
 */
public record CorrectionCandidates(
        List<TagCandidate> tags, List<ParsePatchCandidate> parsePatches, List<SceneExcludeCandidate> sceneExcludes) {

    public CorrectionCandidates {
        tags = List.copyOf(tags);
        parsePatches = List.copyOf(parsePatches);
        sceneExcludes = List.copyOf(sceneExcludes);
    }

    /**
     * @param action APPROVE | REJECT | WITHDRAW
     * @param scope SCENE | CLIP
     */
    public record TagCandidate(
            long evidenceId,
            long taggingId,
            String action,
            String scope,
            String tagType,
            String matchValue,
            String displayName) {}

    /** {@code conditionJson}·{@code patchJson} 은 parse-rule/v1 JSON 원문이다. */
    public record ParsePatchCandidate(long searchRuleId, String conditionJson, String patchJson, Long replacesRuleId) {}

    public record SceneExcludeCandidate(long searchRuleId, long targetSceneId) {}
}
