package com.npick.clip.infrastructure.persistence.repository;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.UnexpectedRollbackException;

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
        } catch (BusinessException failure) {
            throw failure;
        } catch (DataIntegrityViolationException
                | ConstraintViolationException
                | CannotCreateTransactionException
                | UnexpectedRollbackException failure) {
            throw new BusinessException(ClipRuntimeErrorCode.REGISTRATION_FAILED, failure);
        } catch (RuntimeException | Error failure) {
            // Connection/commit errors without a proven rollback must retain the owned files.
            throw new BusinessException(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN, failure);
        }
    }
}
