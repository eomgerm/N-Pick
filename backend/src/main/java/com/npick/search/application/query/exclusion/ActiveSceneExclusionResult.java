package com.npick.search.application.query.exclusion;

import java.util.List;

/**
 * 장면 제외 적용 결과.
 *
 * @param sceneIds 제외 후 원래 순서를 유지한, 요청 페이지의 최대 10개 장면
 * @param excludedScenes 실제 후보에서 빠진 장면과 그 판단에 사용된 활성 규칙. -60의 제외 기록 재료다
 * @param hasMore 이 페이지 뒤로 유효한 후보가 더 있으면 참. 응답의 {@code has_next} 가 된다 (S15P21A501-251)
 */
public record ActiveSceneExclusionResult(List<Long> sceneIds, List<ExcludedScene> excludedScenes, boolean hasMore) {

    public ActiveSceneExclusionResult {
        sceneIds = List.copyOf(sceneIds);
        excludedScenes = List.copyOf(excludedScenes);
    }

    /** 다음 페이지 유무를 따지지 않는 호출(첫 페이지 전용 경로·테스트)은 hasMore=false 로 본다. */
    public ActiveSceneExclusionResult(List<Long> sceneIds, List<ExcludedScene> excludedScenes) {
        this(sceneIds, excludedScenes, false);
    }

    public record ExcludedScene(long sceneId, List<Long> ruleIds) {

        public ExcludedScene {
            ruleIds = List.copyOf(ruleIds);
        }
    }
}
