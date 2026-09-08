package com.npick.clip.domain.policy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationPermissionPolicyTest {
    @ParameterizedTest(name = "externalRequired={0}, externalConfirmed={1}")
    @CsvSource({"false, false", "true, false", "true, true"})
    void requiresConsentOnlyWhenExternalProcessingIsRequired(boolean required, boolean confirmed) {
        if (required && !confirmed) {
            assertThatThrownBy(() -> RegistrationPermissionPolicy.verify(true, confirmed, required))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            error -> assertThat(error.errorCode().code()).isEqualTo("CLIP_400_010"));
        } else {
            assertThatCode(() -> RegistrationPermissionPolicy.verify(true, confirmed, required))
                    .doesNotThrowAnyException();
        }
    }
}
