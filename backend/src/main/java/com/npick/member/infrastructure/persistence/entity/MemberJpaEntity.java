package com.npick.member.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.npick.common.infrastructure.persistence.BaseJpaEntity;

@Entity
@Table(name = "member")
public class MemberJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "login_id", nullable = false)
    private String loginId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "role", nullable = false)
    private String role;

    protected MemberJpaEntity() {}

    public MemberJpaEntity(Long memberId, String loginId, String passwordHash, String name, String role) {
        this.memberId = memberId;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }
}
