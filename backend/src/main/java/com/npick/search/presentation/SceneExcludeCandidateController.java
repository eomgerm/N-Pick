package com.npick.search.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.error.BusinessException;
import com.npick.common.response.ApiResponse;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.search.application.CreateSceneExcludeCandidateCommand;
import com.npick.search.application.CreateSceneExcludeCandidateUseCase;
import com.npick.search.application.DiscardSceneExcludeCandidateUseCase;
import com.npick.search.application.ParseCandidateOutcome;
import com.npick.search.application.error.SceneExcludeCandidateErrorCode;
import com.npick.search.presentation.response.SceneExcludeCandidateResponse;

/**
 * 검수자가 장면 제외(exclude_scene) 후보를 만든다 (S15P21A501-82, F-11).
 *
 * <p>{@code /api/v1/review/**} 는 이미 검수자 전용으로 보안 계층이 막는다. 담당 검수자·검수 중 여부·대상 장면 일치는 서비스가 검증한다.
 */
@RestController
@RequestMapping("/api/v1/review/inquiries")
@Validated
public class SceneExcludeCandidateController {

    private static final String REVIEWER_ROLE = "reviewer";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CreateSceneExcludeCandidateUseCase service;
    private final DiscardSceneExcludeCandidateUseCase discardService;

    public SceneExcludeCandidateController(
            CreateSceneExcludeCandidateUseCase service, DiscardSceneExcludeCandidateUseCase discardService) {
        this.service = service;
        this.discardService = discardService;
    }

    @PostMapping("/{feedbackId}/scene-exclude-candidate")
    public ResponseEntity<ApiResponse<SceneExcludeCandidateResponse>> create(
            @PathVariable long feedbackId,
            @RequestBody String rawBody,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @LoginMember CurrentMember member) {
        JsonNode body = parse(rawBody);
        long targetSceneId = readSceneId(body.get("targetSceneId"));

        ParseCandidateOutcome outcome = service.create(new CreateSceneExcludeCandidateCommand(
                feedbackId,
                member.memberId(),
                REVIEWER_ROLE.equalsIgnoreCase(member.role()),
                idempotencyKey,
                targetSceneId));

        // 새로 만들면 201, 멱등 재생으로 기존 후보를 돌려주면 200.
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ApiResponse.success(SceneExcludeCandidateResponse.of(outcome.searchRuleId(), feedbackId)));
    }

    /**
     * 검수자가 확정 전에 실수로 만든 대기 중인 장면 제외 후보를 취소한다 (S15P21A501-281, F-11).
     *
     * <p>이미 확정되어 켜진 규칙은 서비스가 건드리지 않으므로 확정 뒤에 불러도 조용히 0건으로 끝난다.
     */
    @DeleteMapping("/{feedbackId}/scene-exclude-candidate")
    public ApiResponse<Void> discard(@PathVariable long feedbackId, @LoginMember CurrentMember member) {
        discardService.discard(feedbackId, member.memberId(), REVIEWER_ROLE.equalsIgnoreCase(member.role()));
        return ApiResponse.success();
    }

    /** 장면 ID 는 64bit 라 FE 가 문자열로 보낼 수 있다. 숫자·양의 정수 문자열 둘 다 받는다. 형식 문제는 장면 불일치가 아니라 MALFORMED_REQUEST 로 구분한다. */
    private long readSceneId(JsonNode node) {
        if (node != null && !node.isNull()) {
            // isIntegralNumber 로 정수 노드만 통과. canConvertToLong 단독은 1.9 같은 DoubleNode 도 통과시켜 소수부가 잘린다.
            if (node.isIntegralNumber() && node.canConvertToLong()) {
                return node.asLong();
            }
            if (node.isTextual() && node.asText().matches("[1-9]\\d*")) {
                try {
                    // 20자리 등 long 범위를 넘는 문자열은 여기서 NumberFormatException 이 난다. 숫자 노드의 canConvertToLong 과 강도를 맞춘다.
                    return Long.parseLong(node.asText());
                } catch (NumberFormatException overflow) {
                    throw new BusinessException(SceneExcludeCandidateErrorCode.MALFORMED_REQUEST);
                }
            }
        }
        // targetSceneId 누락·형식 오류. "신고 장면과 다르다"(WRONG_TARGET_SCENE)와 구분해 FE 가 원인을 알 수 있게 한다.
        throw new BusinessException(SceneExcludeCandidateErrorCode.MALFORMED_REQUEST);
    }

    private JsonNode parse(String rawBody) {
        try {
            return OBJECT_MAPPER.readTree(rawBody);
        } catch (JacksonException malformed) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.MALFORMED_REQUEST);
        }
    }
}
