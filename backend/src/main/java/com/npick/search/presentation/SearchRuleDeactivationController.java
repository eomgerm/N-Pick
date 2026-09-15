package com.npick.search.presentation;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.search.application.DeactivateSearchRuleCommand;
import com.npick.search.application.DeactivateSearchRuleService;
import com.npick.search.presentation.request.UpdateSearchRuleActiveRequest;

/**
 * 검수자가 승인된 교정 규칙을 사용 중단한다 (S15P21A501-86, F-11).
 *
 * <p>{@code /api/v1/review/**} 는 보안 계층이 검수자 전용으로 막는다. 중단만 직접 처리하고, 재활성화(active=true)는 검증·승인 경로(-83→-84)를 거쳐야 하므로 서비스가
 * 거부한다. 규칙 본문·과거 실행 기록은 보존되고 하드 삭제하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/review/search-rules")
public class SearchRuleDeactivationController {

    private static final String REVIEWER_ROLE = "reviewer";

    private final DeactivateSearchRuleService service;

    public SearchRuleDeactivationController(DeactivateSearchRuleService service) {
        this.service = service;
    }

    @PatchMapping("/{ruleId}")
    public ApiResponse<Void> update(
            @PathVariable long ruleId,
            @Valid @RequestBody UpdateSearchRuleActiveRequest request,
            @LoginMember CurrentMember member) {
        service.deactivate(new DeactivateSearchRuleCommand(
                ruleId,
                REVIEWER_ROLE.equalsIgnoreCase(member.role()),
                request.active(),
                request.reason()));
        return ApiResponse.success();
    }
}
