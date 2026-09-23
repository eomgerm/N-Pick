package com.npick.tag.presentation;

import java.util.List;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.tag.application.CreateTagCorrectionCandidateCommand;
import com.npick.tag.application.CreateTagCorrectionCandidateUseCase;
import com.npick.tag.application.DiscardTagCorrectionCandidateUseCase;
import com.npick.tag.presentation.request.TagCorrectionRequest;
import com.npick.tag.presentation.response.TagCorrectionCandidateResponse;

/**
 * 검수자가 태그 교정 후보를 만든다 (S15P21A501-160, F-10).
 *
 * <p>{@code /api/v1/review/**} 는 이미 검수자 전용으로 보안 계층이 막는다. 담당 검수자·검수 중 여부·태그 교정 판정은 서비스가 검증한다. 교체는 반려+추가 두 변경안으로 오며 한
 * 트랜잭션으로 저장된다.
 */
@RestController
@RequestMapping("/api/v1/review/inquiries")
public class TagCorrectionCandidateController {

    private static final String REVIEWER_ROLE = "reviewer";

    private final CreateTagCorrectionCandidateUseCase service;
    private final DiscardTagCorrectionCandidateUseCase discardService;

    public TagCorrectionCandidateController(
            CreateTagCorrectionCandidateUseCase service, DiscardTagCorrectionCandidateUseCase discardService) {
        this.service = service;
        this.discardService = discardService;
    }

    @PostMapping("/{feedbackId}/tag-correction-candidate")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TagCorrectionCandidateResponse> create(
            @PathVariable long feedbackId,
            @Valid @RequestBody TagCorrectionRequest request,
            @LoginMember CurrentMember member) {
        List<Long> evidenceIds = service.create(new CreateTagCorrectionCandidateCommand(
                feedbackId, member.memberId(), REVIEWER_ROLE.equalsIgnoreCase(member.role()), request.operations()));
        return ApiResponse.success(TagCorrectionCandidateResponse.of(feedbackId, evidenceIds));
    }

    /**
     * 검수자가 확정 전에 실수로 만든 대기 중인 태그 교정 후보를 취소한다 (S15P21A501-309, F-10).
     *
     * <p>이미 확정된 근거는 서비스가 건드리지 않으므로 확정 뒤에 불러도 조용히 0건으로 끝난다.
     */
    @DeleteMapping("/{feedbackId}/tag-correction-candidate")
    public ApiResponse<Void> discard(@PathVariable long feedbackId, @LoginMember CurrentMember member) {
        discardService.discard(feedbackId, member.memberId(), REVIEWER_ROLE.equalsIgnoreCase(member.role()));
        return ApiResponse.success();
    }
}
