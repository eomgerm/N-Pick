package com.npick.member.presentation.request;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String loginId, @NotBlank String password) {}
