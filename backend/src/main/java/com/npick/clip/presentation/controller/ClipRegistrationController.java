package com.npick.clip.presentation.controller;

import java.io.IOException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.npick.clip.application.command.register.UploadClipUseCase;
import com.npick.clip.application.error.VideoPreparationErrorCode;
import com.npick.clip.presentation.request.ClipUploadRequest;
import com.npick.clip.presentation.response.ClipRegistrationResponse;
import com.npick.common.error.BusinessException;
import com.npick.common.response.ApiResponse;

@RestController
public class ClipRegistrationController {
    private final UploadClipUseCase upload;

    public ClipRegistrationController(UploadClipUseCase upload) {
        this.upload = upload;
    }

    @PostMapping(value = "/api/v1/clips", consumes = "multipart/form-data")
    @io.swagger.v3.oas.annotations.Operation(
            description = "subtitle: UTF-8(BOM 허용) SRT, VTT, JSON. JSON은 "
                    + "{schemaVersion: npick.subtitle/v1, segments: [{s: 정수 ms, e: 정수 ms, t: 문자열}]}만 지원. "
                    + "정의되지 않은 JSON 추가 필드는 거절. 빈 구간·음수·소수·시작 이하인 종료·영상 길이 초과·빈 텍스트는 위치/사유와 함께 파일 전체 거절(CLIP_400_012). "
                    + "종료의 상한은 검사 기준 영상 길이를 올림한 정수 ms이며, 넘으면 문제 구간·상한·초과량(ms)을 안내. "
                    + "시작→종료 순 정렬, 동일 파일 겹침·원문 보존. 시간 보정 없음. MIME은 힌트이며 실제 내용을 검사. "
                    + "script_text는 영상 전체 참고 자료이며 장면 발화가 아님. UTF-8로 읽지 못한 본문은 거절(CLIP_400_013).")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ClipRegistrationResponse> register(
            @Valid @ModelAttribute ClipUploadRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String requestKey) {
        try (var content = request.video().getFirst().getInputStream();
                var subtitle = request.subtitle() == null || request.subtitle().isEmpty()
                        ? null
                        : request.subtitle().getFirst().getInputStream()) {
            return ApiResponse.success(
                    ClipRegistrationResponse.from(upload.upload(request.toCommand(content, subtitle, requestKey))));
        } catch (IOException failure) {
            throw new BusinessException(VideoPreparationErrorCode.INSPECTION_FAILED, failure);
        }
    }
}
