package com.npick.member.application.query;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class MemberSummaryQueryService implements GetMemberSummariesUseCase {
    private final MemberSummaryQueryPort members;

    public MemberSummaryQueryService(MemberSummaryQueryPort members) {
        this.members = members;
    }

    @Override
    public Map<Long, MemberSummary> findByIds(Collection<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) return Map.of();
        return members.findAllByIds(memberIds).stream()
                .collect(Collectors.toUnmodifiableMap(MemberSummary::memberId, Function.identity()));
    }
}
