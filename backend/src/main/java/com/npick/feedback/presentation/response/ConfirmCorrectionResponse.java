package com.npick.feedback.presentation.response;

/** 교정 확정 결과. 확정된 신고는 종료(closed)된다. 색인 갱신이 필요한 구성에서 반영 상태 구분은 색인 도입 시 더한다(F-13 6). */
public record ConfirmCorrectionResponse(long feedbackId, String status) {

    public static ConfirmCorrectionResponse closed(long feedbackId) {
        return new ConfirmCorrectionResponse(feedbackId, "closed");
    }
}
