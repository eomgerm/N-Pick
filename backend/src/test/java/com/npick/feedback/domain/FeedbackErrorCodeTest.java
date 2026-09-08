package com.npick.feedback.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.common.error.ErrorType;
import com.npick.feedback.domain.error.FeedbackErrorCode;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackErrorCodeTest {

    @Test
    @DisplayName("에러 코드는 타입·코드·메시지를 노출한다")
    void exposesTypeCodeMessage() {
        assertThat(FeedbackErrorCode.ALREADY_CLAIMED.type()).isEqualTo(ErrorType.CONFLICT);
        assertThat(FeedbackErrorCode.RESULT_NOT_FOUND.type()).isEqualTo(ErrorType.NOT_FOUND);
        assertThat(FeedbackErrorCode.NOT_OWNER.type()).isEqualTo(ErrorType.FORBIDDEN);
        assertThat(FeedbackErrorCode.ALREADY_CLAIMED.code()).isEqualTo("FEEDBACK_409_001");
        assertThat(FeedbackErrorCode.ALREADY_CLAIMED.message()).isNotBlank();
    }
}
