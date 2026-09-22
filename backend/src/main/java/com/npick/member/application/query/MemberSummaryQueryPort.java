package com.npick.member.application.query;

import java.util.Collection;
import java.util.List;

public interface MemberSummaryQueryPort {
    List<MemberSummary> findAllByIds(Collection<Long> memberIds);
}
