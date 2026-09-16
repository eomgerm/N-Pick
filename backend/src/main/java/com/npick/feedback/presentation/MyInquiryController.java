package com.npick.feedback.presentation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.feedback.application.MyInquiryQueryService;
import com.npick.feedback.presentation.response.MyInquiryListResponse;

/**
 * 검색 화면 사이드바용 「내 문의 기록」 조회 API (S15P21A501-185).
 *
 * <p>검수 API(<code>/api/v1/review/inquiries</code>)와 달리 <b>세션 사용자 본인</b> 소유 문의만 읽는다. 사용자 ID 를 파라미터로 받지
 * 않고 세션에서 결정하므로 다른 사용자 기록으로 우회할 수 없다.
 */
@RestController
@RequestMapping("/api/v1/inquiries")
@Validated
public class MyInquiryController {

    private final MyInquiryQueryService queryService;

    public MyInquiryController(MyInquiryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<MyInquiryListResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @LoginMember CurrentMember member) {
        return ApiResponse.success(
                MyInquiryListResponse.of(queryService.listMine(member.memberId(), page, size), page, size));
    }
}
