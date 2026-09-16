package com.npick.tag.presentation.response;

import java.util.List;

/**
 * 생성된 태그 교정 후보. 모두 확정 전 대기 상태다.
 *
 * <p>id 는 전부 문자열로 준다 — TSID(~4.6e17)는 JavaScript 안전 정수(9.0e15)를 넘어 raw number 로 주면 FE 에서 조용히 깨진다. 한 payload 안에서 규칙을
 * 통일한다.
 */
public record TagCorrectionCandidateResponse(String feedbackId, int created, List<String> evidenceIds) {

    public static TagCorrectionCandidateResponse of(long feedbackId, List<Long> ids) {
        return new TagCorrectionCandidateResponse(
                String.valueOf(feedbackId),
                ids.size(),
                ids.stream().map(String::valueOf).toList());
    }
}
