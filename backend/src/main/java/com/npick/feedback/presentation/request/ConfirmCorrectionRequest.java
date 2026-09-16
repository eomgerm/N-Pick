package com.npick.feedback.presentation.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 교정 확정 요청 본문. 검수자가 확인한 검증 실행 id 를 담는다 — 확정은 이 실행이 승인한 변경안만 반영한다(F-13 2).
 *
 * @param executionId 확정 근거가 되는 검증 실행 id
 */
public record ConfirmCorrectionRequest(@NotNull @Positive Long executionId) {}
