package com.npick.feedback.domain.error;

import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorCode;

public class FeedbackException extends BusinessException {
    public FeedbackException(ErrorCode errorCode) {
        super(errorCode);
    }
}
