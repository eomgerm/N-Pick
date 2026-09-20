package com.npick.search.application.query.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 원 결과와 검증 결과 장면 집합의 차이. 배지 변경·재정렬이 아니라 실제 재계산 결과여야 한다(F-12). */
public final class SceneDiff {

    public record Entered(long sceneId, Map<String, Object> reason) {}

    public record Dropped(long sceneId, String reason) {}

    public record Result(List<Entered> entered, List<Dropped> dropped) {}

    private SceneDiff() {}

    public static Result of(List<Long> originalSceneIds, SearchCandidates verified) {
        Set<Long> original = Set.copyOf(originalSceneIds);
        Set<Long> verifiedIds = verified.scenes().stream()
                .map(SearchCandidates.ScoredScene::sceneId)
                .collect(Collectors.toSet());

        List<Entered> entered = new ArrayList<>();
        for (SearchCandidates.ScoredScene scene : verified.scenes()) {
            if (!original.contains(scene.sceneId())) {
                var reason = new LinkedHashMap<String, Object>();
                reason.put("match", SearchExplain.match(scene, verified.expandedTokens()));
                reason.put("score", SearchExplain.score(scene));
                entered.add(new Entered(scene.sceneId(), reason));
            }
        }
        List<Dropped> dropped = new ArrayList<>();
        for (Long sceneId : originalSceneIds) {
            if (!verifiedIds.contains(sceneId)) {
                dropped.add(new Dropped(sceneId, droppedReason(sceneId, verified)));
            }
        }
        return new Result(entered, dropped);
    }

    /** 후보 제외 규칙에 걸렸으면 그 사유, false-hit guard 에 걸렸으면 그 사유, 아니면 순위/컷오프 이탈. */
    private static String droppedReason(long sceneId, SearchCandidates verified) {
        boolean excluded = verified.appliedExcludes().stream().anyMatch(e -> e.sceneId() == sceneId);
        if (excluded) return "approved_scene_exclusion";
        boolean guardExcluded = verified.guard().excluded().stream().anyMatch(v -> v.sceneId() == sceneId);
        if (guardExcluded) return "false_hit_guard";
        return "score_drop";
    }
}
