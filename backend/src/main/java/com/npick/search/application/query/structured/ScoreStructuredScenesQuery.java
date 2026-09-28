package com.npick.search.application.query.structured;

import java.util.List;
import java.util.Objects;

import com.npick.search.domain.model.QueryResolution;

/**
 * #47·#48 및 승인된 해석 교정까지 적용된 최종 해석과 lexical/dense 등의 후보 ID.
 *
 * @param searchTokens 질의 정규화 토큰({@code 형태/품사}) 원본. 320 의 BM25 범용어 제외 <b>전</b> 값이다 — 키워드 태그 매칭은 자기 제외 목록을 쓴다
 *     (S15P21A501-321)
 * @param expandedPhrases 확장어 구 토큰. 키워드 태그 후보 편입에만 쓰고 점수에는 쓰지 않는다 (S15P21A501-48)
 */
public record ScoreStructuredScenesQuery(
        QueryResolution finalResolution,
        List<Long> candidateSceneIds,
        List<String> searchTokens,
        List<List<String>> expandedPhrases) {
    public ScoreStructuredScenesQuery {
        Objects.requireNonNull(finalResolution, "finalResolution");
        candidateSceneIds = List.copyOf(candidateSceneIds);
        if (candidateSceneIds.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("Scene IDs must be positive");
        }
        searchTokens = List.copyOf(searchTokens);
        expandedPhrases = expandedPhrases.stream().map(List::copyOf).toList();
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

    /** 키워드 태그 매칭 없이 해석 축만 본다. 321 이전 호출부가 쓴다. */
    public ScoreStructuredScenesQuery(QueryResolution finalResolution, List<Long> candidateSceneIds) {
        this(finalResolution, candidateSceneIds, List.of(), List.of());
    }
}
