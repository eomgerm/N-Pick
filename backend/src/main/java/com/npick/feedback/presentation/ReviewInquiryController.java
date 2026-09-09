package com.npick.feedback.presentation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.feedback.application.InquiryReviewService;
import com.npick.feedback.presentation.response.InquiryDetailResponse;
import com.npick.feedback.presentation.response.InquiryListResponse;

@RestController
@RequestMapping("/api/v1/review")
public class ReviewInquiryController {

    private final InquiryReviewService reviewService;

    public ReviewInquiryController(InquiryReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/inquiries")
    public ApiResponse<InquiryListResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        InquiryListResponse response =
                InquiryListResponse.of(reviewService.list(status, safePage, safeSize), safePage, safeSize);
        return ApiResponse.success(response);
    }

    @GetMapping("/inquiries/{feedbackId}")
    public ApiResponse<InquiryDetailResponse> detail(@PathVariable long feedbackId) {
        return ApiResponse.success(InquiryDetailResponse.from(reviewService.detail(feedbackId)));
    }

    @PostMapping("/inquiries/{feedbackId}/claim")
    public ApiResponse<Void> claim(
            @PathVariable long feedbackId,
            // FE가 재시도 안전용으로 보낼 수 있어 헤더는 받되 저장/dedup하지 않는다. 재시도 안전성은 서비스의 소유자 멱등이 보장한다.
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @LoginMember CurrentMember member) {
        reviewService.claim(feedbackId, member.memberId());
        return ApiResponse.success();
    }
}
