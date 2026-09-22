package com.npick.member.application.query;

import java.util.Collection;
import java.util.Map;

public interface GetMemberSummariesUseCase {
    /** 없는 ID 는 결과에서 빠진다. 호출자가 한 번에 모아 묻는다 (N+1 방지). */
    Map<Long, MemberSummary> findByIds(Collection<Long> memberIds);
}
