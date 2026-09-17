package com.npick.search.presentation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.search.application.GetMySearchHistoryDetailUseCase;
import com.npick.search.application.ListMySearchHistoryUseCase;
import com.npick.search.presentation.response.SearchHistoryDetailResponse;
import com.npick.search.presentation.response.SearchHistoryListResponse;

/**
 * 검색 화면 사이드바용 「내 검색 기록」 조회 API (S15P21A501-198).
 *
 * <p>사용자 ID 를 파라미터로 받지 않고 세션에서 결정하므로 다른 사용자 기록으로 우회할 수 없다. EDITOR·REVIEWER 모두
 * 여기서는 자기 기록만 본다 — 검수자의 타인 문의 처리는 {@code /api/v1/review/**} 가 담당한다.
 *
 * <p>{@code size} 는 범위를 벗어나면 clamp 하지 않고 400 으로 거부한다. 조용히 고쳐 주면 FE 가 잘못된 요청을 모른다.
 */
@RestController
@RequestMapping("/api/v1/search/history")
@Validated
public class SearchHistoryController {

    private final ListMySearchHistoryUseCase listMySearchHistoryUseCase;
    private final GetMySearchHistoryDetailUseCase getMySearchHistoryDetailUseCase;

    public SearchHistoryController(
            ListMySearchHistoryUseCase listMySearchHistoryUseCase,
            GetMySearchHistoryDetailUseCase getMySearchHistoryDetailUseCase) {
        this.listMySearchHistoryUseCase = listMySearchHistoryUseCase;
        this.getMySearchHistoryDetailUseCase = getMySearchHistoryDetailUseCase;
    }

    @GetMapping
    public ApiResponse<SearchHistoryListResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @LoginMember CurrentMember member) {
        return ApiResponse.success(SearchHistoryListResponse.of(
                listMySearchHistoryUseCase.listMine(member.memberId(), page, size), page, size));
    }

    @GetMapping("/{searchExecutionId}")
    public ApiResponse<SearchHistoryDetailResponse> detail(
            @PathVariable @Min(1) long searchExecutionId, @LoginMember CurrentMember member) {
        return ApiResponse.success(SearchHistoryDetailResponse.from(
                getMySearchHistoryDetailUseCase.detailMine(searchExecutionId, member.memberId())));
    }
}
