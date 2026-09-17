package com.npick.feedback.presentation;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.feedback.application.ConfirmCorrectionCommand;
import com.npick.feedback.application.ConfirmCorrectionUseCase;
import com.npick.feedback.presentation.request.ConfirmCorrectionRequest;

/**
 * 검수자가 검증한 교정을 확정한다 (S15P21A501-84, F-13).
 *
 * <p>{@code /api/v1/review/**} 는 보안 계층이 검수자 전용으로 막는다. 담당 검수자·검수 중·교정 판정·검증 실행 일치·drift 는 서비스가 검증하고, 태그·규칙 확정과 신고 종료를 한
 * 트랜잭션으로 처리한다.
 */
@RestController
@RequestMapping("/api/v1/review/inquiries")
public class CorrectionConfirmController {

    private static final String REVIEWER_ROLE = "reviewer";

    private final ConfirmCorrectionUseCase service;

    public CorrectionConfirmController(ConfirmCorrectionUseCase service) {
        this.service = service;
    }

    // 성공은 body 없는 200 이다(web-api.md §6.4). 확정 결과는 신고 상세 재조회로 확인한다.
    @PostMapping("/{feedbackId}/confirm")
    public ApiResponse<Void> confirm(
            @PathVariable long feedbackId,
            @Valid @RequestBody ConfirmCorrectionRequest request,
            @LoginMember CurrentMember member) {
        service.confirm(new ConfirmCorrectionCommand(
                feedbackId,
                member.memberId(),
                REVIEWER_ROLE.equalsIgnoreCase(member.role()),
                request.executionId()));
        return ApiResponse.success();
    }
}
