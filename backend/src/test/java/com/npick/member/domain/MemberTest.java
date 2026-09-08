package com.npick.member.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.member.domain.model.Member;
import com.npick.member.domain.model.Role;

import static org.assertj.core.api.Assertions.assertThat;

class MemberTest {

    @Test
    @DisplayName("멤버는 식별자·로그인아이디·역할·비밀번호해시를 보유한다")
    void holdsIdentityLoginIdRoleAndPasswordHash() {
        Member member = new Member(1L, "reporter01", "김기자", Role.EDITOR, "$2a$hash");

        assertThat(member.memberId()).isEqualTo(1L);
        assertThat(member.loginId()).isEqualTo("reporter01");
        assertThat(member.role()).isEqualTo(Role.EDITOR);
        assertThat(member.passwordHash()).isEqualTo("$2a$hash");
    }
}
