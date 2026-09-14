package com.npick.tag.presentation.response;

import java.util.List;

/** 생성된 태그 교정 후보. 근거 id 는 64bit 라 정밀도 보존을 위해 문자열로 준다. 모두 확정 전 대기 상태다. */
public record TagCorrectionCandidateResponse(long feedbackId, int created, List<String> evidenceIds) {

    public static TagCorrectionCandidateResponse of(long feedbackId, List<Long> ids) {
        return new TagCorrectionCandidateResponse(
                feedbackId, ids.size(), ids.stream().map(String::valueOf).toList());
    }
}
