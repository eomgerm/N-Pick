package com.npick.feedback.presentation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.feedback.application.GetCorrectionCandidatesUseCase;
import com.npick.feedback.presentation.response.CorrectionCandidatesResponse;

/**
 * 담당 검수자가 이 신고의 대기 중인 교정 후보(태그·해석·장면 제외)를 읽는다 (S15P21A501-317).
 *
 * <p>새로고침 뒤 작성 중이던 교정을 복원하는 용도다. {@code /api/v1/review/**} 는 보안 계층이 검수자 전용으로 막고, 담당 검수자 확인은 서비스가 한다.
 */
@RestController
@RequestMapping("/api/v1/review/inquiries")
public class CorrectionCandidateQueryController {

    private final GetCorrectionCandidatesUseCase service;

    public CorrectionCandidateQueryController(GetCorrectionCandidatesUseCase service) {
        this.service = service;
    }

    @GetMapping("/{feedbackId}/correction-candidates")
    public ApiResponse<CorrectionCandidatesResponse> get(
            @PathVariable long feedbackId, @LoginMember CurrentMember member) {
        return ApiResponse.success(CorrectionCandidatesResponse.from(service.get(feedbackId, member.memberId())));
    }
}
