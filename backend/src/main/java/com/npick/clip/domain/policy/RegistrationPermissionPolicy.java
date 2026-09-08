package com.npick.clip.domain.policy;

import com.npick.clip.domain.error.RegistrationPermissionErrorCode;
import com.npick.common.error.BusinessException;

public final class RegistrationPermissionPolicy {
    private RegistrationPermissionPolicy() {}

    public static void verify(boolean rightsConfirmed, boolean externalConfirmed, boolean externalRequired) {
        if (!rightsConfirmed) throw new BusinessException(RegistrationPermissionErrorCode.RIGHTS_NOT_CONFIRMED);
        if (externalRequired && !externalConfirmed) {
            throw new BusinessException(RegistrationPermissionErrorCode.EXTERNAL_NOT_CONFIRMED);
        }
    }
}
