package com.npick.clip.application.command;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.npick.clip.application.command.register.RegisterClipCommand;
import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.RegisterClipUseCase;
import com.npick.clip.application.command.register.RegistrationOutcome;
import com.npick.clip.domain.model.InitialClipRegistration;
import com.npick.clip.domain.model.InitialClipRegistration.PipelineDefinition;
import com.npick.clip.domain.model.InitialClipRegistration.SourceType;
import com.npick.clip.domain.repository.ClipRegistrationRepository;

@Service
public class ClipRegistrationService implements RegisterClipUseCase {
    private final ClipRegistrationRepository repository;

    public ClipRegistrationService(ClipRegistrationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RegisterClipResult register(RegisterClipCommand command) {
        var registration = new InitialClipRegistration(
                command.clipId(),
                command.pipelineRunId(),
                SourceType.fromValue(command.sourceType()),
                command.storageKey(),
                command.contentHash(),
                command.title(),
                command.transcriptFileKey(),
                command.scriptText(),
                command.registeredById(),
                command.broadcastDate(),
                command.filmedDate(),
                new PipelineDefinition(command.pipelineVersion(), command.stageNames()),
                Instant.now());
        repository.save(registration);
        return new RegisterClipResult(
                registration.clipId(),
                registration.pipelineRunId(),
                registration.status(),
                RegistrationOutcome.CREATED);
    }
}
