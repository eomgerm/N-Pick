package com.npick.feedback.presentation.request;

import jakarta.validation.constraints.Size;

// 문의 내용은 선택 입력이라 null·빈 값을 허용하되, 화면을 우회한 API 요청도 저장 전에 상한을 넘지 못하게 막는다 (S15P21A501-273).
// 길이는 FE 와 같은 UTF-16 코드 단위(String.length)로 센다 — 일반 한글 1, 일부 이모지 2.
public record CreateInquiryRequest(
        @Size(max = 2000, message = "문의 내용은 2,000자 이내로 입력해 주세요") String comment) {}
