package com.npick.feedback.presentation;

import java.util.List;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.feedback.application.InquiryReviewService;
import com.npick.feedback.presentation.request.ResolveInquiryRequest;
import com.npick.feedback.presentation.response.InquiryDetailResponse;
import com.npick.feedback.presentation.response.InquiryListItemResponse;

@RestController
@RequestMapping("/api/v1/review")
public class ReviewInquiryController {

    private final InquiryReviewService reviewService;

    public ReviewInquiryController(InquiryReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/inquiries")
    public ApiResponse<List<InquiryListItemResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<InquiryListItemResponse> items = reviewService.list(status, safePage, safeSize).stream()
                .map(InquiryListItemResponse::from)
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/inquiries/{feedbackId}")
    public ApiResponse<InquiryDetailResponse> detail(@PathVariable long feedbackId) {
        return ApiResponse.success(InquiryDetailResponse.from(reviewService.detail(feedbackId)));
    }

    @PostMapping("/inquiries/{feedbackId}/claim")
    public ApiResponse<Void> claim(@PathVariable long feedbackId, @LoginMember CurrentMember member) {
        reviewService.claim(feedbackId, member.memberId());
        return ApiResponse.success();
    }

    // 처리 결과는 reviewing 동안 덮어쓰기 가능한 멱등 단일값이라 PUT 이다.
    @PutMapping("/inquiries/{feedbackId}/resolution")
    public ApiResponse<Void> resolve(
            @PathVariable long feedbackId,
            @Valid @RequestBody ResolveInquiryRequest request,
            @LoginMember CurrentMember member) {
        reviewService.resolve(feedbackId, member.memberId(), request.resolution(), request.note());
        return ApiResponse.success();
    }
}
