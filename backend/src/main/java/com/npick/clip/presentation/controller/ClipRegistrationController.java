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
