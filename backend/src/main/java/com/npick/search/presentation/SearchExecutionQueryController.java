package com.npick.search.presentation;

import jakarta.validation.constraints.Positive;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.search.application.query.execution.GetSearchExecutionUseCase;
import com.npick.search.presentation.response.SearchExecutionDetailResponse;

@RestController
@RequestMapping("/api/v1/search/executions")
public class SearchExecutionQueryController {
    private final GetSearchExecutionUseCase detail;

    public SearchExecutionQueryController(GetSearchExecutionUseCase detail) {
        this.detail = detail;
    }

    @GetMapping("/{executionId}")
    @io.swagger.v3.oas.annotations.Operation(
            summary = "검색 실행 기록 상세 조회",
            description = "검색을 실행한 본인 또는 검수자만 당시 해석, 적용 규칙, 제외 사유와 결과 스냅샷을 조회한다.")
    public ApiResponse<SearchExecutionDetailResponse> detail(
            @PathVariable @Positive long executionId, @LoginMember CurrentMember member) {
        boolean reviewer = "reviewer".equalsIgnoreCase(member.role());
        return ApiResponse.success(
                SearchExecutionDetailResponse.from(detail.get(executionId, member.memberId(), reviewer)));
    }
}
