package com.npick.search.presentation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 규칙 사용 상태 변경 요청 (S15P21A501-86).
 *
 * @param active 목표 상태. {@code false}만 직접 처리(중단)한다. {@code true}(재활성화)는 검증 경로를 거쳐야 하므로 서비스가 거부한다
 * @param reason 중단 사유. 필수로 받아 검증하되 저장하지 않는다(FRD 감사 de-scope)
 */
public record UpdateSearchRuleActiveRequest(@NotNull Boolean active, @NotBlank @Size(max = 500) String reason) {}
