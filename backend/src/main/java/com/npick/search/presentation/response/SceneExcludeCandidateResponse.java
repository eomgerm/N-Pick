package com.npick.search.presentation.response;

/** 생성된 장면 제외 후보. 켜기 전까지 일반 검색에 영향을 주지 않으므로 {@code active} 는 항상 {@code false} 다. */
public record SceneExcludeCandidateResponse(long searchRuleId, long feedbackId, boolean active) {

    public static SceneExcludeCandidateResponse of(long searchRuleId, long feedbackId) {
        return new SceneExcludeCandidateResponse(searchRuleId, feedbackId, false);
    }
}
