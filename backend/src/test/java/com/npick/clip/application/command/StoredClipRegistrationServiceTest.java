package com.npick.clip.application.command;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.npick.clip.application.command.prepare.PrepareVideoResult;
import com.npick.clip.application.command.register.RegisterClipUseCase;
import com.npick.clip.application.command.register.StoreAndRegisterClipCommand;
import com.npick.clip.application.command.store.StoreVideoResult;
import com.npick.clip.application.error.ClipRuntimeErrorCode;
import com.npick.clip.application.port.VideoStoragePort;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoredClipRegistrationServiceTest {
    private final VideoStoragePort storage = mock(VideoStoragePort.class);
    private final RegisterClipUseCase registration = mock(RegisterClipUseCase.class);
    private final PrepareVideoResult video = mock(PrepareVideoResult.class);
    private final StoreVideoResult stored = mock(StoreVideoResult.class);
    private final StoredClipRegistrationService service = new StoredClipRegistrationService(storage, registration);

    private StoreAndRegisterClipCommand command() {
        return new StoreAndRegisterClipCommand(
                video, 1, 2, "archive", null, null, null, null, null, 3, "test-v1", List.of("scene_detection"));
    }

    @Test
    void preservesOriginalFailureAndCleanupFailure() {
        when(storage.store(1, video)).thenReturn(stored);
        var failure = new IllegalStateException("db failed");
        var cleanup = new IllegalStateException("cleanup failed");
        when(registration.register(any())).thenThrow(failure);
        doThrow(cleanup).when(stored).discard();
        assertThatThrownBy(() -> service.register(command())).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(cleanup);
        verify(stored).discard();
    }

    @Test
    void retainsFileWhenTransactionOutcomeIsUnknown() {
        when(storage.store(1, video)).thenReturn(stored);
        var failure = new BusinessException(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
        when(registration.register(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.register(command())).isSameAs(failure);
        verify(stored, never()).discard();
    }

    @Test
    void neverRegistersWhenStorageFails() {
        when(storage.store(1, video)).thenThrow(new IllegalStateException("storage failed"));
        assertThatThrownBy(() -> service.register(command())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(registration);
    }
}
