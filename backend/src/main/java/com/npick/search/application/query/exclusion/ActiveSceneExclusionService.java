package com.npick.search.application.query.exclusion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.npick.search.application.query.exclusion.ActiveSceneExclusionResult.ExcludedScene;

/** 승인된 exact 장면 제외를 적용한 뒤 원래 순서대로 최대 10개를 고른다 (F-06). */
@Service
public class ActiveSceneExclusionService implements ApplyActiveSceneExclusionsUseCase {

    private static final int MAX_RESULTS = 10;

    private final FindActiveSceneExclusionsQueryPort exclusions;

    public ActiveSceneExclusionService(FindActiveSceneExclusionsQueryPort exclusions) {
        this.exclusions = exclusions;
    }

    @Override
    public ActiveSceneExclusionResult apply(ApplyActiveSceneExclusionsQuery query) {
        Objects.requireNonNull(query, "query");
        Map<Long, List<Long>> ruleIdsByScene = new LinkedHashMap<>();
        exclusions
                .find(query.search())
                .forEach(rule -> ruleIdsByScene
                        .computeIfAbsent(rule.sceneId(), ignored -> new ArrayList<>())
                        .add(rule.ruleId()));

        var selected = new ArrayList<Long>(MAX_RESULTS);
        var excluded = new ArrayList<ExcludedScene>();
        for (Long sceneId : query.rankedSceneIds()) {
            List<Long> ruleIds = ruleIdsByScene.get(sceneId);
            if (ruleIds != null) {
                excluded.add(new ExcludedScene(sceneId, ruleIds));
                continue;
            }
            selected.add(sceneId);
            if (selected.size() == MAX_RESULTS) {
                break;
            }
        }
        return new ActiveSceneExclusionResult(selected, excluded);
    }
}
