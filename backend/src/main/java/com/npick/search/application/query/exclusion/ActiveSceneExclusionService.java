package com.npick.search.application.query.exclusion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.npick.search.application.query.exclusion.ActiveSceneExclusionResult.ExcludedScene;

/** 승인된 exact 장면 제외를 적용한 뒤 원래 순서대로 요청 페이지의 10개를 고른다 (F-06, 더보기 offset S15P21A501-251). */
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

        // page 는 유효(비제외) 후보 기준으로 센다. MAX_RESULTS 는 페이지 크기다.
        int skip = query.page() * MAX_RESULTS;
        var selected = new ArrayList<Long>(MAX_RESULTS);
        var excluded = new ArrayList<ExcludedScene>();
        int validSeen = 0;
        boolean hasMore = false;
        for (Long sceneId : query.rankedSceneIds()) {
            List<Long> ruleIds = ruleIdsByScene.get(sceneId);
            boolean isExcluded = ruleIds != null;

            if (selected.size() == MAX_RESULTS) {
                // 이 페이지는 다 찼다. 유효 후보가 하나라도 더 있으면 다음 페이지가 있다.
                if (!isExcluded) {
                    hasMore = true;
                    break;
                }
                continue;
            }
            if (isExcluded) {
                // 이 페이지 구간에 든 제외만 기록한다 — 앞 페이지에서 이미 지나간 제외는 이 페이지의 사유가 아니다.
                if (validSeen >= skip) {
                    excluded.add(new ExcludedScene(sceneId, ruleIds));
                }
                continue;
            }
            // 유효 후보. 앞 페이지 몫이면 건너뛴다.
            if (validSeen < skip) {
                validSeen++;
                continue;
            }
            selected.add(sceneId);
            validSeen++;
        }
        return new ActiveSceneExclusionResult(selected, excluded, hasMore);
    }
}
