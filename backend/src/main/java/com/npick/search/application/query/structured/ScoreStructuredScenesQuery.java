package com.npick.search.application.query.structured;

import java.util.List;
import java.util.Objects;

import com.npick.search.domain.model.QueryResolution;

/** #47·#48 및 승인된 해석 교정까지 적용된 최종 해석과 lexical/dense 등의 후보 ID. */
public record ScoreStructuredScenesQuery(QueryResolution finalResolution, List<Long> candidateSceneIds) {
    public ScoreStructuredScenesQuery {
        Objects.requireNonNull(finalResolution, "finalResolution");
        candidateSceneIds = List.copyOf(candidateSceneIds);
        if (candidateSceneIds.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("Scene IDs must be positive");
        }
        // 원본 해석·출처를 보존하면서 호출 뒤 외부 리스트 변경의 영향을 막는다.
        var r = finalResolution;
        finalResolution = new QueryResolution(
                r.schemaVersion(),
                r.intent(),
                List.copyOf(r.dateWindows()),
                List.copyOf(r.incidentNames()),
                List.copyOf(r.entities()),
                List.copyOf(r.locations()),
                List.copyOf(r.classifications()),
                List.copyOf(r.expandedTerms()),
                r.confidence());
    }
}
