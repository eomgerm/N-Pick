package com.npick.search.application.query.search;

import java.util.List;

/**
 * 이 신고의 대기 교정 후보. tag 근거는 confirmed=false, 규칙은 active=false 로 대기 중이다.
 *
 * @param resolution 신고 처리 방향 (tag_correction/patch_parse/exclude_scene)
 * @param tagEvidenceIds 확정 대기 태그 근거 (없으면 빈 목록)
 * @param approvedRuleId 활성화 대상 규칙 R2 (없으면 null)
 * @param replacedRuleId 교체로 비활성화할 규칙 R1 (없으면 null)
 */
public record PendingCandidates(
        String resolution, List<Long> tagEvidenceIds, Long approvedRuleId, Long replacedRuleId) {

    public PendingCandidates {
        tagEvidenceIds = tagEvidenceIds == null ? List.of() : List.copyOf(tagEvidenceIds);
    }
}
