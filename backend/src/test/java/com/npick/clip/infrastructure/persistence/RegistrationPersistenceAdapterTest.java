package com.npick.clip.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

import com.npick.clip.application.command.register.RegisterClipUseCase;
import com.npick.clip.application.error.ClipRuntimeErrorCode;
import com.npick.clip.infrastructure.persistence.repository.RegistrationPersistenceAdapter;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationPersistenceAdapterTest {
    @Test
    void distinguishesKnownFailureFromUnknownTransactionOutcome() {
        RegisterClipUseCase failed = command -> {
            throw new DataIntegrityViolationException("private SQL");
        };
        RegisterClipUseCase unknown = command -> {
            throw new TransactionSystemException("private connection");
        };
        assertThatThrownBy(() -> new RegistrationPersistenceAdapter(failed).register(null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ClipRuntimeErrorCode.REGISTRATION_FAILED);
                    assertThat(e.getMessage()).doesNotContain("private");
                });
        assertThatThrownBy(() -> new RegistrationPersistenceAdapter(unknown).register(null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN));
    }
}
