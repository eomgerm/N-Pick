package com.npick.member.domain.model;

public record Member(long memberId, String loginId, String name, Role role, String passwordHash) {}
