package com.npick.search.presentation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.search.application.query.search.VerificationResult;
import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;
import com.npick.search.presentation.response.VerificationResultResponse;

/**
 * 후보 검증 자동 재검색 (web-api §6.4, S15P21A501-83, FRD F-12).
 *
 * <p>{@code /api/v1/review/**} 는 이미 검수자 전용으로 보안 계층이 막는다(SecurityConfig). 신고 없음(404)·담당 검수자
 * 아님(403)·검수 중 아님/대기 후보 없음(409)은 {@code VerificationSearchService.verify}가 검사한다 — 형제 endpoint인
 * {@code SceneExcludeCandidateController}와 같은 책임 분리(세부 검증은 서비스, 컨트롤러는 위임만).
 */
@RestController
@RequestMapping("/api/v1/review/inquiries")
public class VerificationController {

    private final VerifyCorrectionCandidatesUseCase useCase;

    public VerificationController(VerifyCorrectionCandidatesUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/{feedbackId}/verify")
    public ResponseEntity<ApiResponse<VerificationResultResponse>> verify(
            @PathVariable long feedbackId, @LoginMember CurrentMember member) {
        VerificationResult result = useCase.verify(feedbackId, member.memberId());
        return ResponseEntity.ok(ApiResponse.success(VerificationResultResponse.of(result)));
    }
}
