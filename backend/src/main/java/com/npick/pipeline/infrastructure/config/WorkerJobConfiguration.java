package com.npick.pipeline.infrastructure.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.npick.clip.application.query.media.GetWorkerMediaInputUseCase;
import com.npick.pipeline.application.command.WorkerInputAssembler;
import com.npick.pipeline.application.command.WorkerJobTransportService;
import com.npick.pipeline.application.port.WorkerExecutionPort;
import com.npick.pipeline.infrastructure.artifact.LocalWorkerArtifactAdapter;

/** HTTP transport binds to the same executor used by internal callers. */
@Configuration
@ConditionalOnProperty(name = "npick.worker-jobs.enabled", havingValue = "true")
public class WorkerJobConfiguration {
    @Bean
    WorkerExecutionPort workerExecution(
            com.npick.pipeline.application.command.claim.ClaimStageUseCase claims,
            com.npick.pipeline.application.command.heartbeat.HeartbeatStageUseCase heartbeats,
            com.npick.pipeline.application.command.complete.CompleteStageUseCase completions,
            com.npick.pipeline.domain.repository.PipelineRunRepository runs,
            GetWorkerMediaInputUseCase media,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        return new com.npick.pipeline.application.command.WorkerExecutionBinding(
                claims,
                heartbeats,
                completions,
                runs,
                media,
                new org.springframework.transaction.support.TransactionTemplate(transactions));
    }

    @Bean
    com.npick.pipeline.infrastructure.persistence.JdbcWorkerStageOutputAdapter workerStageOutput(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            tools.jackson.databind.ObjectMapper mapper,
            LocalWorkerArtifactAdapter artifacts) {
        return new com.npick.pipeline.infrastructure.persistence.JdbcWorkerStageOutputAdapter(jdbc, mapper, artifacts);
    }

    @Bean
    LocalWorkerArtifactAdapter workerArtifacts(@Value("${npick.clip-registration.media-root}") Path root) {
        return new LocalWorkerArtifactAdapter(root);
    }

    @Bean
    WorkerJobTransportService workerJobTransport(
            WorkerExecutionPort execution,
            LocalWorkerArtifactAdapter artifacts,
            @Value("${npick.worker-jobs.fleet:local}") String fleet,
            @Value("${npick.worker-jobs.shared-media-volume:false}") boolean sharedVolume,
            GetWorkerMediaInputUseCase media) {
        return new WorkerJobTransportService(
                execution, artifacts, fleet, new WorkerInputAssembler(media, sharedVolume));
    }
}
