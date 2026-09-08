package com.npick.clip.infrastructure.persistence.mapper;

import java.util.Locale;

import com.npick.clip.domain.model.InitialClipRegistration;
import com.npick.clip.domain.model.InitialClipRegistration.DateEvidence;
import com.npick.clip.infrastructure.persistence.entity.ClipJpaEntity;
import com.npick.clip.infrastructure.persistence.entity.PipelineRunJpaEntity;
import com.npick.clip.infrastructure.persistence.entity.TagEvidenceJpaEntity;
import com.npick.clip.infrastructure.persistence.entity.TaggingJpaEntity;

public final class ClipRegistrationPersistenceMapper {
    private ClipRegistrationPersistenceMapper() {}

    public static ClipJpaEntity clip(InitialClipRegistration r) {
        return ClipJpaEntity.builder()
                .clipId(r.clipId())
                .sourceType(r.sourceType().name().toLowerCase(Locale.ROOT))
                .storageKey(r.storageKey())
                .contentHash(r.contentHash())
                .title(r.title())
                .transcriptFileKey(r.transcriptFileKey())
                .transcriptSource(r.transcriptSource())
                .scriptText(r.scriptText())
                .registeredById(r.registeredById())
                .activePipelineRunId(r.activePipelineRunId())
                .createdAt(r.registeredAt())
                .updatedAt(r.registeredAt())
                .build();
    }

    public static PipelineRunJpaEntity run(InitialClipRegistration r, String stageStatesJson) {
        return PipelineRunJpaEntity.builder()
                .pipelineRunId(r.pipelineRunId())
                .clipId(r.clipId())
                .processingNo(r.processingNo())
                .pipelineVersion(r.pipeline().version())
                .status(r.status())
                .stageStatesJson(stageStatesJson)
                .errorCode(r.errorCode())
                .startedAt(r.startedAt())
                .finishedAt(r.finishedAt())
                .createdAt(r.registeredAt())
                .updatedAt(r.registeredAt())
                .build();
    }

    public static TaggingJpaEntity tagging(InitialClipRegistration r, DateEvidence date, long taggingId, long tagId) {
        return TaggingJpaEntity.builder()
                .taggingId(taggingId)
                .clipId(r.clipId())
                .sceneId(date.sceneId())
                .tagId(tagId)
                .createdAt(r.registeredAt())
                .build();
    }

    public static TagEvidenceJpaEntity evidence(
            InitialClipRegistration r, DateEvidence date, long evidenceId, long taggingId) {
        return TagEvidenceJpaEntity.builder()
                .evidenceId(evidenceId)
                .taggingId(taggingId)
                .source(date.source())
                .confidence(date.confidence())
                .verificationStatus(date.verificationStatus())
                .sourceRefType(date.sourceRefType())
                .sourceRefId(date.sourceRefId())
                .sourceFeedbackId(date.sourceFeedbackId())
                .createdAt(r.registeredAt())
                .build();
    }
}
