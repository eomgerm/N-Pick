package com.npick.feedback.presentation.request;

import jakarta.validation.constraints.Size;

// note 는 종료성 판정에서 필수이고 resolution_note(text) 로 저장된다. 인증된 REVIEWER 라도 과도한 본문을 넣지 못하도록 상한을 둔다.
public record ResolveInquiryRequest(
        String resolution, @Size(max = 2000) String note) {}
