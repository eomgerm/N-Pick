package com.npick.search.presentation;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.search.application.query.search.ExecuteSearchQuery;
import com.npick.search.application.query.search.ExecuteSearchUseCase;
import com.npick.search.presentation.request.SearchRequest;
import com.npick.search.presentation.response.SearchResponse;

/** 장면 검색 (web-api §5, FRD F-05). */
@RestController
@RequestMapping("/api/v1/search")
@Validated
public class SearchController {

    private final ExecuteSearchUseCase useCase;

    public SearchController(ExecuteSearchUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 검색 한 번을 실행한다.
     *
     * <p>클라이언트가 보낸 순위·검증 상태는 받지 않는다 (§6.3). 요청에 있는 것은 검색어와 사용자가 직접 건 날짜 필터뿐이고, 나머지는 전부 서버가 정한다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SearchResponse>> search(
            @Valid @RequestBody SearchRequest request, @LoginMember CurrentMember member) {
        var result = useCase.execute(new ExecuteSearchQuery(
                request.query(),
                request.toDateFilters(),
                member.memberId(),
                request.pageOrDefault(),
                request.parentExecutionId()));
        return ResponseEntity.ok(ApiResponse.success(SearchResponse.of(result)));
    }
}
