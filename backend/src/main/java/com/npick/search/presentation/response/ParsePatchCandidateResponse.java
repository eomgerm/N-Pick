package com.npick.search.presentation.response;

/** 생성된 patch_parse 후보. 켜기 전까지 일반 검색에 영향을 주지 않으므로 {@code active} 는 항상 {@code false} 다. */
public record ParsePatchCandidateResponse(long searchRuleId, long feedbackId, boolean active) {

    public static ParsePatchCandidateResponse of(long searchRuleId, long feedbackId) {
        return new ParsePatchCandidateResponse(searchRuleId, feedbackId, false);
    }
}
