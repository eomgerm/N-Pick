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
import com.npick.search.application.CreateSceneExcludeCandidateCommand;
import com.npick.search.application.CreateSceneExcludeCandidateService;
import com.npick.search.application.error.SceneExcludeCandidateErrorCode;
import com.npick.search.presentation.response.SceneExcludeCandidateResponse;

/**
 * 검수자가 장면 제외(exclude_scene) 후보를 만든다 (S15P21A501-82, F-11).
 *
 * <p>{@code /api/v1/review/**} 는 이미 검수자 전용으로 보안 계층이 막는다. 담당 검수자·검수 중 여부·대상 장면 일치는 서비스가 검증한다.
 */
@RestController
@RequestMapping("/api/v1/review/inquiries")
public class SceneExcludeCandidateController {

    private static final String REVIEWER_ROLE = "reviewer";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CreateSceneExcludeCandidateService service;

    public SceneExcludeCandidateController(CreateSceneExcludeCandidateService service) {
        this.service = service;
    }

    @PostMapping("/{feedbackId}/scene-exclude-candidate")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SceneExcludeCandidateResponse> create(
            @PathVariable long feedbackId,
            @RequestBody String rawBody,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @LoginMember CurrentMember member) {
        JsonNode body = parse(rawBody);
        long targetSceneId = readSceneId(body.get("targetSceneId"));

        long searchRuleId = service.create(new CreateSceneExcludeCandidateCommand(
                feedbackId,
                member.memberId(),
                REVIEWER_ROLE.equalsIgnoreCase(member.role()),
                idempotencyKey,
                targetSceneId));
        return ApiResponse.success(SceneExcludeCandidateResponse.of(searchRuleId, feedbackId));
    }

    /** 장면 ID 는 64bit 라 FE 가 문자열로 보낼 수 있다. 숫자·양의 정수 문자열 둘 다 받는다. */
    private long readSceneId(JsonNode node) {
        if (node != null && !node.isNull()) {
            if (node.canConvertToLong()) {
                return node.asLong();
            }
            if (node.isTextual() && node.asText().matches("[1-9]\\d*")) {
                return Long.parseLong(node.asText());
            }
        }
        throw new BusinessException(SceneExcludeCandidateErrorCode.WRONG_TARGET_SCENE);
    }

    private JsonNode parse(String rawBody) {
        try {
            return OBJECT_MAPPER.readTree(rawBody);
        } catch (JacksonException malformed) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.WRONG_TARGET_SCENE);
        }
    }
}
