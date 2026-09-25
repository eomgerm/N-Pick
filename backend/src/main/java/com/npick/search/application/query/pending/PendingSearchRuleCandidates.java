package com.npick.search.application.query.pending;

import java.util.List;

/**
 * 이 신고가 만든 대기 중인 규칙 후보({@code search_rule.active=false}) (S15P21A501-317). 해석 교정과 장면 제외를 나눠 담는다.
 *
 * @param parsePatches patch_parse 후보
 * @param sceneExcludes exclude_scene 후보
 */
public record PendingSearchRuleCandidates(List<ParsePatch> parsePatches, List<SceneExclude> sceneExcludes) {

    public PendingSearchRuleCandidates {
        parsePatches = List.copyOf(parsePatches);
        sceneExcludes = List.copyOf(sceneExcludes);
    }

    /**
     * @param searchRuleId 후보 규칙 id
     * @param conditionJson parse-rule/v1 조건 JSON 원문
     * @param patchJson parse-rule/v1 변경 JSON 원문
     * @param replacesRuleId 교체할 활성 규칙. 없으면 {@code null}
     */
    public record ParsePatch(long searchRuleId, String conditionJson, String patchJson, Long replacesRuleId) {}

    /**
     * @param searchRuleId 후보 규칙 id
     * @param targetSceneId 제외할 장면
     */
    public record SceneExclude(long searchRuleId, long targetSceneId) {}
}
