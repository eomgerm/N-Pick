package com.npick.clip.infrastructure.config;

import java.io.IOException;
import java.nio.file.Files;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import com.npick.clip.application.command.ClipUploadService;
import com.npick.clip.application.command.StoredClipRegistrationService;
import com.npick.clip.application.command.VideoPreparationService;
import com.npick.clip.application.command.prepare.PrepareVideoUseCase;
import com.npick.clip.application.command.register.RegisterClipUseCase;
import com.npick.clip.application.command.register.UploadClipUseCase;
import com.npick.clip.application.error.ClipRuntimeErrorCode;
import com.npick.clip.application.port.ClipRegistrationContextPort;
import com.npick.clip.application.port.RegistrationActorPort;
import com.npick.clip.application.port.RegistrationDeduplicationPort;
import com.npick.clip.application.port.TranscriptIntakePort;
import com.npick.clip.infrastructure.media.FfmpegVideoValidator;
import com.npick.clip.infrastructure.media.FfprobeVideoReader;
import com.npick.clip.infrastructure.media.LocalVideoInspectionAdapter;
import com.npick.clip.infrastructure.media.LocalVideoStorageAdapter;
import com.npick.clip.infrastructure.media.UploadedVideoValidator;
import com.npick.common.error.BusinessException;
import com.npick.common.persistence.TsidGenerator;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClipRegistrationProperties.class)
public class ClipRegistrationConfiguration {
    @Bean
    ClipRegistrationContextPort clipRegistrationContext(
            ClipRegistrationProperties properties, ObjectProvider<RegistrationActorPort> actors) {
        return () -> {
            var actor = actors.getIfAvailable();
            if (actor == null) throw new BusinessException(ClipRuntimeErrorCode.AUTHENTICATION_UNAVAILABLE);
            long memberId = actor.requireReviewerId();
            if (memberId <= 0) throw new BusinessException(ClipRuntimeErrorCode.AUTHENTICATION_UNAVAILABLE);
            ready(properties);
            return new ClipRegistrationContextPort.Context(
                    memberId,
                    TsidGenerator.generate(),
                    TsidGenerator.generate(),
                    properties.pipelineVersion(),
                    properties.stageNames());
        };
    }

    @Bean
    UploadClipUseCase uploadClipUseCase(
            ClipRegistrationProperties properties,
            ClipRegistrationContextPort context,
            RegisterClipUseCase registration,
            ObjectMapper mapper,
            ObjectProvider<RegistrationDeduplicationPort> deduplications,
            ObjectProvider<TranscriptIntakePort> transcripts) {
        PrepareVideoUseCase preparation = command -> {
            ready(properties);
            try {
                Files.createDirectories(properties.uploadRoot());
                Files.createDirectories(properties.mediaRoot());
                if (Files.isSameFile(properties.uploadRoot(), properties.mediaRoot())) {
                    throw new IOException("임시 및 원본 저장 위치가 같습니다.");
                }
            } catch (IOException failure) {
                throw new BusinessException(ClipRuntimeErrorCode.CONFIGURATION_UNAVAILABLE, failure);
            }
            return new VideoPreparationService(new LocalVideoInspectionAdapter(new UploadedVideoValidator(
                            properties.uploadRoot(),
                            new FfprobeVideoReader("ffprobe", properties.probeTimeout(), mapper),
                            new FfmpegVideoValidator("ffmpeg", properties.decodeTimeout()),
                            properties.inputLimits())))
                    .prepare(command);
        };
        return new ClipUploadService(
                context,
                preparation,
                new StoredClipRegistrationService(
                        (clipId, video) -> {
                            ready(properties);
                            return new LocalVideoStorageAdapter(properties.mediaRoot()).store(clipId, video);
                        },
                        new com.npick.clip.infrastructure.persistence.repository.RegistrationPersistenceAdapter(
                                registration)),
                (key, actor, hash, request, create) -> {
                    var adapter = deduplications.getIfAvailable();
                    if (adapter == null) throw new BusinessException(ClipRuntimeErrorCode.INTEGRATION_UNAVAILABLE);
                    return adapter.register(key, actor, hash, request, create);
                },
                (subtitle, duration, clipId) -> {
                    var adapter = transcripts.getIfAvailable();
                    if (adapter == null) throw new BusinessException(ClipRuntimeErrorCode.INTEGRATION_UNAVAILABLE);
                    return adapter.receive(subtitle, duration, clipId);
                },
                (rights, external) -> com.npick.clip.domain.policy.RegistrationPermissionPolicy.verify(
                        rights, external, properties.externalProcessingRequired()));
    }

    private static void ready(ClipRegistrationProperties properties) {
        try {
            properties.validateReady();
        } catch (IllegalArgumentException failure) {
            throw new BusinessException(ClipRuntimeErrorCode.CONFIGURATION_UNAVAILABLE, failure);
        }
    }
}
