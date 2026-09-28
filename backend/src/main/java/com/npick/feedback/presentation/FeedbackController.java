package com.npick.feedback.presentation;

import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.feedback.application.FeedbackIntakeService;
import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.presentation.request.CreateInquiryRequest;
import com.npick.feedback.presentation.request.UpdateInquiryRequest;
import com.npick.feedback.presentation.response.InquiryResponse;

@RestController
@RequestMapping("/api/v1")
public class FeedbackController {

    private final FeedbackIntakeService intakeService;

    public FeedbackController(FeedbackIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping("/search/results/{resultId}/inquiries")
    public ApiResponse<InquiryResponse> submit(
            @PathVariable long resultId,
            @RequestBody(required = false) CreateInquiryRequest request,
            @LoginMember CurrentMember member) {
        String comment = request == null ? null : request.comment();
        Feedback fb = intakeService.submit(resultId, member.memberId(), comment);
        return ApiResponse.success(InquiryResponse.from(fb));
    }

    @PatchMapping("/inquiries/{feedbackId}")
    public ApiResponse<Void> edit(
            @PathVariable long feedbackId,
            @RequestBody UpdateInquiryRequest request,
            @LoginMember CurrentMember member) {
        intakeService.editComment(feedbackId, member.memberId(), request.comment());
        return ApiResponse.success();
    }
}
