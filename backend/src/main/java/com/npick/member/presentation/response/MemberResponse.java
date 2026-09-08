package com.npick.member.presentation.response;

import com.npick.common.security.CurrentMember;

public record MemberResponse(long memberId, String loginId, String role) {

    public static MemberResponse from(CurrentMember member) {
        return new MemberResponse(member.memberId(), member.loginId(), member.role());
    }
}
