package com.npick.pipeline.infrastructure.config;

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
            com.npick.pipeline.application.port.StageOutputPort outputs,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        return new com.npick.pipeline.application.command.WorkerExecutionBinding(
                claims,
                heartbeats,
                completions,
                runs,
                media,
                new org.springframework.transaction.support.TransactionTemplate(transactions),
                outputs);
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
