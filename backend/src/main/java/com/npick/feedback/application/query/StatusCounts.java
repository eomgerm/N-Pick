package com.npick.feedback.application.query;

/** 상태 탭 배지용 — 현재 필터와 무관한 전체 상태별 문의 건수. */
public record StatusCounts(long open, long reviewing, long closed) {}
