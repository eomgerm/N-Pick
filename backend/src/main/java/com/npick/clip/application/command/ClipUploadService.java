package com.npick.clip.application.command;

import com.npick.clip.application.command.prepare.PrepareVideoCommand;
import com.npick.clip.application.command.prepare.PrepareVideoUseCase;
import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.StoreAndRegisterClipCommand;
import com.npick.clip.application.command.register.StoreAndRegisterClipUseCase;
import com.npick.clip.application.command.register.UploadClipCommand;
import com.npick.clip.application.command.register.UploadClipUseCase;
import com.npick.clip.application.error.ClipRuntimeErrorCode;
import com.npick.clip.application.port.ClipRegistrationContextPort;
import com.npick.clip.application.port.RegistrationDeduplicationPort;
import com.npick.clip.application.port.TranscriptIntakePort;
import com.npick.clip.domain.policy.RegistrationPermissionPolicy;
import com.npick.common.error.BusinessException;

public final class ClipUploadService implements UploadClipUseCase {
    private final ClipRegistrationContextPort context;
    private final PrepareVideoUseCase preparation;
    private final StoreAndRegisterClipUseCase registration;
    private final RegistrationDeduplicationPort deduplication;
    private final TranscriptIntakePort transcripts;
    private final RegistrationPermissionPolicy permission = new RegistrationPermissionPolicy();

    public ClipUploadService(
            ClipRegistrationContextPort context,
            PrepareVideoUseCase preparation,
            StoreAndRegisterClipUseCase registration,
            RegistrationDeduplicationPort deduplication,
            TranscriptIntakePort transcripts) {
        this.context = context;
        this.preparation = preparation;
        this.registration = registration;
        this.deduplication = deduplication;
        this.transcripts = transcripts;
    }

    public RegisterClipResult upload(UploadClipCommand command) {
        var server = context.requireAuthorizedContext();
        permission.verify(
                command.rightsConfirmed(), command.externalProcessingConfirmed(), server.externalProcessingRequired());
        try (var video = preparation.prepare(new PrepareVideoCommand(command.content()));
                var transcript = command.subtitle() == null
                        ? null
                        : transcripts.receive(
                                command.subtitle(), video.metadata().durationSeconds(), server.clipId())) {
            var request = new RegistrationDeduplicationPort.RequestData(
                    command.sourceType(),
                    command.title(),
                    command.broadcastDate(),
                    command.filmedDate(),
                    command.scriptText(),
                    transcript == null ? null : transcript.contentHash(),
                    command.rightsConfirmed(),
                    command.externalProcessingConfirmed());
            return deduplication.register(
                    command.requestKey(), server.registeredById(), video.contentHash(), request, () -> {
                        try {
                            var result = registration.register(new StoreAndRegisterClipCommand(
                                    video,
                                    server.clipId(),
                                    server.pipelineRunId(),
                                    command.sourceType(),
                                    command.title(),
                                    command.broadcastDate(),
                                    command.filmedDate(),
                                    transcript == null ? null : transcript.storageKey(),
                                    command.scriptText(),
                                    server.registeredById(),
                                    server.pipelineVersion(),
                                    server.stageNames()));
                            if (transcript != null) transcript.retain();
                            return result;
                        } catch (BusinessException failure) {
                            if (transcript != null
                                    && failure.errorCode() == ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN) {
                                transcript.retain();
                            }
                            throw failure;
                        }
                    });
        }
    }
}
