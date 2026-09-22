package com.npick.search.presentation;

import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.error.BusinessException;
import com.npick.common.error.CommonErrorCode;
import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.search.application.DeleteMySearchHistoryUseCase;
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

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 10;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    private final ListMySearchHistoryUseCase listMySearchHistoryUseCase;
    private final GetMySearchHistoryDetailUseCase getMySearchHistoryDetailUseCase;
    private final DeleteMySearchHistoryUseCase deleteMySearchHistoryUseCase;

    public SearchHistoryController(
            ListMySearchHistoryUseCase listMySearchHistoryUseCase,
            GetMySearchHistoryDetailUseCase getMySearchHistoryDetailUseCase,
            DeleteMySearchHistoryUseCase deleteMySearchHistoryUseCase) {
        this.listMySearchHistoryUseCase = listMySearchHistoryUseCase;
        this.getMySearchHistoryDetailUseCase = getMySearchHistoryDetailUseCase;
        this.deleteMySearchHistoryUseCase = deleteMySearchHistoryUseCase;
    }

    @GetMapping
    public ApiResponse<SearchHistoryListResponse> list(
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size,
            @LoginMember CurrentMember member) {
        int pageNumber = pagingValue(page, DEFAULT_PAGE, 0, Integer.MAX_VALUE);
        int pageSize = pagingValue(size, DEFAULT_SIZE, MIN_SIZE, MAX_SIZE);
        return ApiResponse.success(SearchHistoryListResponse.of(
                listMySearchHistoryUseCase.listMine(member.memberId(), pageNumber, pageSize),
                pageNumber,
                pageSize));
    }

    @GetMapping("/{searchExecutionId}")
    public ApiResponse<SearchHistoryDetailResponse> detail(
            @PathVariable @Min(1) long searchExecutionId, @LoginMember CurrentMember member) {
        return ApiResponse.success(SearchHistoryDetailResponse.from(
                getMySearchHistoryDetailUseCase.detailMine(searchExecutionId, member.memberId())));
    }

    /**
     * 기록 하나를 내 목록에서 지운다 (S15P21A501-276).
     *
     * <p>저장은 보존한다 — 감사 조회와 문의 상세가 같은 행을 읽으므로 화면에서만 감춘다. 같은 요청을 두 번 보내도
     * 성공이다.
     */
    @DeleteMapping("/{searchExecutionId}")
    public ApiResponse<Void> delete(@PathVariable @Min(1) long searchExecutionId, @LoginMember CurrentMember member) {
        deleteMySearchHistoryUseCase.deleteMine(searchExecutionId, member.memberId());
        return ApiResponse.success();
    }

    /**
     * 생략과 빈 값을 구분해 읽는다.
     *
     * <p>{@code @RequestParam(defaultValue = ...)} 은 파라미터가 <b>빈 값일 때도</b> 기본값을 적용한다. 그래서
     * {@code ?page=&size=} 가 400 이 아니라 {@code page=0, size=10} 으로 성공했다(MR !126 리뷰 P2). 정본은 생략한
     * 경우에만 기본값을 적용하고 빈 값은 거부하라고 요구하므로 문자열로 받아 직접 판정한다.
     *
     * <p>형식 오류(빈 값·정수 아님)는 {@code COMM_400}, 범위 위반은 {@code COMM_400_001} 이다. 범위를 벗어난 값을
     * clamp 하지 않는다 — 조용히 고쳐 주면 FE 가 잘못된 요청을 모른다.
     */
    private static int pagingValue(String raw, int defaultValue, int min, int max) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw.isBlank()) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        if (value < min || value > max) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return value;
    }
}
