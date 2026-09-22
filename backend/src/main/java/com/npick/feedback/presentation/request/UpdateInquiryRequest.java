package com.npick.feedback.presentation.request;

import jakarta.validation.constraints.Size;

// 수정 요청도 등록과 같은 상한을 적용한다 (S15P21A501-273). 길이는 UTF-16 코드 단위다.
public record UpdateInquiryRequest(
        @Size(max = 2000, message = "문의 내용은 2,000자 이내로 입력해 주세요") String comment) {}
