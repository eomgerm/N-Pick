package com.npick.feedback.application;

/**
 * 교정 확정 요청 (S15P21A501-84, F-13).
 *
 * @param feedbackId 확정할 신고
 * @param reviewerId 요청 검수자
 * @param reviewerRole 요청자가 검수자 역할인가
 * @param executionId 검수자가 확인한 검증 실행. 확정은 이 실행이 승인한 변경안만 반영한다(F-13 "다른 변경안을 끼워 넣을 수 없다")
 */
public record ConfirmCorrectionCommand(long feedbackId, long reviewerId, boolean reviewerRole, long executionId) {}
