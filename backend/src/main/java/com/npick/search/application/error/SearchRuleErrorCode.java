package com.npick.search.application.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * 해석 규칙 조회 실패 (설계 정본 §13 의 application ErrorCode).
 *
 * <p>규칙 <b>내용</b>의 문제는 여기 오지 않는다. 문법·계약 비호환은 {@code ParseRuleOutcome.Status.SKIPPED_INCOMPATIBLE} 로 그 실행 안에 기록되고 검색은
 * 계속된다 (F-14). 여기는 규칙을 <b>읽지 못한</b> 경우다.
 *
 * <p><b>왜 검색을 실패시키는가.</b> FRD §6.2 는 「활성 규칙 조회 실패 → 사람의 결정을 조용히 건너뛰지 않고 검색 실패·재시도 안내」를 요구한다. 조회가 실패했을 때 빈 목록으로 검색을 이어가면
 * 검수자가 승인한 교정이 없는 것처럼 동작하는데, 사용자는 그 사실을 알 수 없다. AI 해석 실패가 BM25 로 우회되는 것과 다르다 — 그쪽은 누락을 안내할 수 있고 이쪽은 무엇이 빠졌는지조차 모른다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SearchRuleErrorCode implements ErrorCode {
    /** 활성 규칙을 조회하지 못했다. 빈 목록으로 대체하지 않고 검색을 실패시킨다 (§6.2). */
    RULE_LOOKUP_FAILED(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_201", "적용할 교정 규칙을 확인하지 못했다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
