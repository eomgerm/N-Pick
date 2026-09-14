package com.npick.clip.application.command;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.npick.clip.application.command.prepare.PrepareVideoCommand;
import com.npick.clip.application.command.prepare.PrepareVideoUseCase;
import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.StoreAndRegisterClipCommand;
import com.npick.clip.application.command.register.StoreAndRegisterClipUseCase;
import com.npick.clip.application.command.register.UploadClipCommand;
import com.npick.clip.application.command.register.UploadClipUseCase;
import com.npick.clip.application.error.ClipRuntimeErrorCode;
import com.npick.clip.application.error.TranscriptErrorCode;
import com.npick.clip.application.port.ClipRegistrationContextPort;
import com.npick.clip.application.port.RegistrationDeduplicationPort;
import com.npick.clip.application.port.TranscriptIntakePort;
import com.npick.clip.domain.policy.RegistrationPermissionPolicy;
import com.npick.common.error.BusinessException;

public final class ClipUploadService implements UploadClipUseCase {
    private static final Logger log = LoggerFactory.getLogger(ClipUploadService.class);
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
        RegisterClipResult completed = null;
        var creationAttempted = new AtomicBoolean();
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
            completed = deduplication.register(
                    command.requestKey(), server.registeredById(), video.contentHash(), request, () -> {
                        creationAttempted.set(true);
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
            return completed;
        } catch (BusinessException failure) {
            // 기존 등록 결과가 확정된 재전송에 한해 자막 정리 오류만 응답에서 분리한다.
            // 등록 실패·결과 불명확·영상 정리 등 다른 오류는 기존대로 전달한다.
            if (completed != null
                    && !creationAttempted.get()
                    && failure.errorCode() == TranscriptErrorCode.CLEANUP_FAILED
                    && failure.getSuppressed().length == 0) {
                log.warn(
                        "Duplicate registration subtitle cleanup failed: candidateClipId={}, code={}",
                        server.clipId(),
                        failure.errorCode().code());
                return completed;
            }
            throw failure;
        }
    }
}
