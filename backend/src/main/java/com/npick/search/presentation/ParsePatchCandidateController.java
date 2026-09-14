package com.npick.search.presentation;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.error.BusinessException;
import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.search.application.CreateParsePatchCandidateCommand;
import com.npick.search.application.CreateParsePatchCandidateService;
import com.npick.search.application.error.ParseRuleCandidateErrorCode;
import com.npick.search.presentation.response.ParsePatchCandidateResponse;

/**
 * 검수자가 해석 교정(patch_parse) 규칙 후보를 만든다 (S15P21A501-81, F-11).
 *
 * <p>{@code /api/v1/review/**} 는 이미 검수자 전용으로 보안 계층이 막는다. 담당 검수자·검수 중 여부는 서비스가 검증한다.
 *
 * <p>{@code condition}·{@code patch} 는 parse-rule/v1 JSON 객체다. 본문을 원문 그대로 받아 두 객체를 문자열로 보존한다 — 도메인 형식 정본이
 * {@link com.npick.search.domain.model.ParseRule} 이므로 프레젠테이션에서 구조를 다시 정의하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/review/inquiries")
public class ParsePatchCandidateController {

    private static final String REVIEWER_ROLE = "reviewer";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CreateParsePatchCandidateService service;

    public ParsePatchCandidateController(CreateParsePatchCandidateService service) {
        this.service = service;
    }

    @PostMapping("/{feedbackId}/parse-patch-candidate")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ParsePatchCandidateResponse> create(
            @PathVariable long feedbackId,
            @RequestBody String rawBody,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @LoginMember CurrentMember member) {
        JsonNode body = parse(rawBody);
        JsonNode condition = body.get("condition");
        JsonNode patch = body.get("patch");
        if (condition == null || condition.isNull() || patch == null || patch.isNull()) {
            throw new BusinessException(ParseRuleCandidateErrorCode.INVALID_CANDIDATE);
        }
        Long replacesRuleId =
                body.hasNonNull("replacesRuleId") ? body.get("replacesRuleId").asLong() : null;

        long searchRuleId = service.create(new CreateParsePatchCandidateCommand(
                feedbackId,
                member.memberId(),
                REVIEWER_ROLE.equalsIgnoreCase(member.role()),
                idempotencyKey,
                condition.toString(),
                patch.toString(),
                replacesRuleId));
        return ApiResponse.success(ParsePatchCandidateResponse.of(searchRuleId, feedbackId));
    }

    private JsonNode parse(String rawBody) {
        try {
            return OBJECT_MAPPER.readTree(rawBody);
        } catch (JacksonException malformed) {
            throw new BusinessException(ParseRuleCandidateErrorCode.INVALID_CANDIDATE);
        }
    }
}
