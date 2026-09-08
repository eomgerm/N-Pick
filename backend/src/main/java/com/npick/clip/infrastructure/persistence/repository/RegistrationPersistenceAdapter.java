package com.npick.clip.infrastructure.persistence.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;

import com.npick.clip.application.command.register.RegisterClipCommand;
import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.RegisterClipUseCase;
import com.npick.clip.application.error.ClipRuntimeErrorCode;
import com.npick.common.error.BusinessException;

/** 트랜잭션 프록시 바깥에서 커밋 실패까지 내부 오류 계약으로 변환한다. */
public final class RegistrationPersistenceAdapter implements RegisterClipUseCase {
    private final RegisterClipUseCase transaction;

    public RegistrationPersistenceAdapter(RegisterClipUseCase transaction) {
        this.transaction = transaction;
    }

    public RegisterClipResult register(RegisterClipCommand command) {
        try {
            return transaction.register(command);
        } catch (TransactionSystemException failure) {
            throw new BusinessException(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN, failure);
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ClipRuntimeErrorCode.REGISTRATION_FAILED, failure);
        }
    }
}
