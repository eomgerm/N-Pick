package com.npick.search.application.query.structured;

import java.util.Collection;
import java.util.List;

/** 태그 존재와 독립적으로 현재 활성 처리·미삭제 클립에 속한 장면을 가른다. 개수 제한은 없다. */
public interface FindEligibleScenesQueryPort {
    /** 요청한 sceneId는 eligible과 ineligible 중 정확히 한쪽에 한 번 나타난다. 두 목록 모두 sceneId 오름차순이다. */
    Eligibility find(Collection<Long> sceneIds);

    record Eligibility(List<EligibleScene> eligible, List<StructuredScoresResult.Ineligible> ineligible) {
        public Eligibility {
            eligible = List.copyOf(eligible);
            ineligible = List.copyOf(ineligible);
        }
    }

    record EligibleScene(long sceneId, long clipId) {}
}
