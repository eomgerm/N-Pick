package com.npick.clip.application.command;

import com.npick.clip.application.command.register.RegisterClipCommand;
import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.RegisterClipUseCase;
import com.npick.clip.application.command.register.StoreAndRegisterClipCommand;
import com.npick.clip.application.command.register.StoreAndRegisterClipUseCase;
import com.npick.clip.application.error.ClipRuntimeErrorCode;
import com.npick.clip.application.port.VideoStoragePort;
import com.npick.common.error.BusinessException;

/** 파일 작업에 DB 트랜잭션을 걸지 않는다. registration에는 별도 트랜잭션의 프록시를 주입한다. */
public final class StoredClipRegistrationService implements StoreAndRegisterClipUseCase {
    private final VideoStoragePort storage;
    private final RegisterClipUseCase registration;

    public StoredClipRegistrationService(VideoStoragePort storage, RegisterClipUseCase registration) {
        this.storage = storage;
        this.registration = registration;
    }

    @Override
    public RegisterClipResult register(StoreAndRegisterClipCommand command) {
        var stored = storage.store(command.clipId(), command.video());
        try {
            return registration.register(new RegisterClipCommand(
                    command.sourceType(),
                    command.title(),
                    command.broadcastDate(),
                    command.filmedDate(),
                    stored.storageKey(),
                    command.video().contentHash(),
                    command.transcriptFileKey(),
                    command.scriptText(),
                    command.registeredById(),
                    command.clipId(),
                    command.pipelineRunId(),
                    command.pipelineVersion(),
                    command.stageNames()));
        } catch (RuntimeException | Error failure) {
            if (failure instanceof BusinessException business
                    && business.errorCode() == ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN) {
                throw failure;
            }
            try {
                stored.discard();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
