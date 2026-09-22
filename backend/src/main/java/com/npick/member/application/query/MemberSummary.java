package com.npick.member.application.query;

/** 다른 도메인 모듈에 공개하는 계정 요약. 공개 범위는 로그인 ID 까지다 (docs/contracts/web-api.md §6.5). */
public record MemberSummary(long memberId, String loginId) {}
