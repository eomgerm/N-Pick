package com.npick.search.application.query.structured;

import java.util.Collection;
import java.util.List;

/** 태그 존재와 독립적으로 현재 활성 처리·미삭제 클립에 속한 장면만 반환한다. 개수 제한은 없다. */
public interface FindEligibleScenesQueryPort {
    List<EligibleScene> find(Collection<Long> sceneIds);

    record EligibleScene(long sceneId, long clipId) {}
}
