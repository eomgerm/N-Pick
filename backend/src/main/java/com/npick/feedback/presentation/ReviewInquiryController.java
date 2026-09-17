package com.npick.feedback.presentation;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.feedback.application.ClaimInquiryUseCase;
import com.npick.feedback.application.GetInquiryDetailUseCase;
import com.npick.feedback.application.ListInquiriesUseCase;
import com.npick.feedback.application.ResolveInquiryUseCase;
import com.npick.feedback.presentation.request.ResolveInquiryRequest;
import com.npick.feedback.presentation.response.InquiryDetailResponse;
import com.npick.feedback.presentation.response.InquiryListResponse;

@RestController
@RequestMapping("/api/v1/review")
public class ReviewInquiryController {

    private final ListInquiriesUseCase listInquiriesUseCase;
    private final GetInquiryDetailUseCase getInquiryDetailUseCase;
    private final ClaimInquiryUseCase claimInquiryUseCase;
    private final ResolveInquiryUseCase resolveInquiryUseCase;

    public ReviewInquiryController(
            ListInquiriesUseCase listInquiriesUseCase,
            GetInquiryDetailUseCase getInquiryDetailUseCase,
            ClaimInquiryUseCase claimInquiryUseCase,
            ResolveInquiryUseCase resolveInquiryUseCase) {
        this.listInquiriesUseCase = listInquiriesUseCase;
        this.getInquiryDetailUseCase = getInquiryDetailUseCase;
        this.claimInquiryUseCase = claimInquiryUseCase;
        this.resolveInquiryUseCase = resolveInquiryUseCase;
    }

    @GetMapping("/inquiries")
    public ApiResponse<InquiryListResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        InquiryListResponse response =
                InquiryListResponse.of(listInquiriesUseCase.list(status, safePage, safeSize), safePage, safeSize);
        return ApiResponse.success(response);
    }

    @GetMapping("/inquiries/{feedbackId}")
    public ApiResponse<InquiryDetailResponse> detail(@PathVariable long feedbackId) {
        return ApiResponse.success(InquiryDetailResponse.from(getInquiryDetailUseCase.detail(feedbackId)));
    }

    @PostMapping("/inquiries/{feedbackId}/claim")
    public ApiResponse<Void> claim(
            @PathVariable long feedbackId,
            // FE가 재시도 안전용으로 보낼 수 있어 헤더는 받되 저장/dedup하지 않는다. 재시도 안전성은 서비스의 소유자 멱등이 보장한다.
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @LoginMember CurrentMember member) {
        claimInquiryUseCase.claim(feedbackId, member.memberId());
        return ApiResponse.success();
    }

    // 처리 결과는 reviewing 동안 덮어쓰기 가능한 멱등 단일값이라 PUT 이다.
    @PutMapping("/inquiries/{feedbackId}/resolution")
    public ApiResponse<Void> resolve(
            @PathVariable long feedbackId,
            @Valid @RequestBody ResolveInquiryRequest request,
            @LoginMember CurrentMember member) {
        resolveInquiryUseCase.resolve(feedbackId, member.memberId(), request.resolution(), request.note());
        return ApiResponse.success();
    }
}
