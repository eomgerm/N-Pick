package com.npick.search.presentation.response;

/**
 * 생성된 patch_parse 후보. 켜기 전까지 일반 검색에 영향을 주지 않으므로 {@code active} 는 항상 {@code false} 다.
 *
 * <p>id 는 문자열로 준다 — TSID(~4.6e17)는 JavaScript 안전 정수(9.0e15)를 넘어 raw number 로 주면 FE 에서 조용히 깨진다
 * (web-api.md §2.3·§8). 한 payload 안에서 규칙을 통일한다.
 */
public record ParsePatchCandidateResponse(String searchRuleId, String feedbackId, boolean active) {

    public static ParsePatchCandidateResponse of(long searchRuleId, long feedbackId) {
        return new ParsePatchCandidateResponse(String.valueOf(searchRuleId), String.valueOf(feedbackId), false);
    }
}
