package com.npick.member.domain.error;

import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorCode;

public class MemberAuthException extends BusinessException {

    public MemberAuthException(ErrorCode errorCode) {
        super(errorCode);
    }
}
