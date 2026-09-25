package com.npick.tag.application;

import java.util.List;

/**
 * 태그 교정 후보 저장 결과 (S15P21A501-317).
 *
 * @param evidenceIds 변경안마다 하나씩, 요청 순서대로의 근거 id. 이미 대기 중인 같은 판단을 재사용했으면 그 근거 id 다
 * @param newlyCreated 이번 요청으로 새로 만든 근거 수. 전부 재사용이면 0
 */
public record CreateTagCorrectionCandidateResult(List<Long> evidenceIds, int newlyCreated) {

    public CreateTagCorrectionCandidateResult {
        evidenceIds = List.copyOf(evidenceIds);
    }
}
