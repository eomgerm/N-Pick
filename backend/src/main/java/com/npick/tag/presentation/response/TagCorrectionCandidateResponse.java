package com.npick.tag.presentation.response;

import java.util.List;

import com.npick.tag.application.CreateTagCorrectionCandidateResult;

/**
 * 생성된 태그 교정 후보. 모두 확정 전 대기 상태다.
 *
 * <p>id 는 전부 문자열로 준다 — TSID(~4.6e17)는 JavaScript 안전 정수(9.0e15)를 넘어 raw number 로 주면 FE 에서 조용히 깨진다. 한 payload 안에서 규칙을
 * 통일한다.
 *
 * <p>{@code created} 는 변경안마다 하나씩 돌려준 근거 id 수({@code evidenceIds} 길이)다 — 이미 대기 중인 같은 판단을 재사용한 것도 센다. FE 파서가
 * {@code created == evidenceIds.length} 를 요구하므로 그 의미를 유지하고, 이번 요청으로 실제로 새로 만든 수는 {@code newlyCreated} 로 따로 준다
 * (S15P21A501-317).
 */
public record TagCorrectionCandidateResponse(
        String feedbackId, int created, int newlyCreated, List<String> evidenceIds) {

    public static TagCorrectionCandidateResponse of(long feedbackId, CreateTagCorrectionCandidateResult result) {
        return new TagCorrectionCandidateResponse(
                String.valueOf(feedbackId),
                result.evidenceIds().size(),
                result.newlyCreated(),
                result.evidenceIds().stream().map(String::valueOf).toList());
    }
}
