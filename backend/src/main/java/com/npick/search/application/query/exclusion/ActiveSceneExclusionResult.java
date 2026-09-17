package com.npick.search.application.query.exclusion;

import java.util.List;

/**
 * 장면 제외 적용 결과.
 *
 * @param sceneIds 제외 후 원래 순서를 유지한 최대 10개 장면
 * @param excludedScenes 실제 후보에서 빠진 장면과 그 판단에 사용된 활성 규칙. -60의 제외 기록 재료다
 */
public record ActiveSceneExclusionResult(List<Long> sceneIds, List<ExcludedScene> excludedScenes) {

    public ActiveSceneExclusionResult {
        sceneIds = List.copyOf(sceneIds);
        excludedScenes = List.copyOf(excludedScenes);
    }

    public record ExcludedScene(long sceneId, List<Long> ruleIds) {

        public ExcludedScene {
            ruleIds = List.copyOf(ruleIds);
        }
    }
}
