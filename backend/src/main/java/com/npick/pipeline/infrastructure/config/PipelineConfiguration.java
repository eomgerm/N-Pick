package com.npick.pipeline.infrastructure.config;

import java.time.Clock;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.command.StageExecutionService;
import com.npick.pipeline.application.error.JobErrorCode;
import com.npick.pipeline.application.port.JobJsonPort;
import com.npick.pipeline.application.port.StageOutputPort;
import com.npick.pipeline.application.query.definition.GetPipelineDefinitionUseCase;
import com.npick.pipeline.domain.repository.PipelineRunRepository;

@Configuration(proxyBeanMethods = false)
public class PipelineConfiguration {
    @Bean
    StageExecutionService stageExecutionService(
            PipelineRunRepository runs,
            GetPipelineDefinitionUseCase definitions,
            ObjectProvider<StageOutputPort> outputs,
            JobJsonPort json,
            com.npick.pipeline.application.port.ProcessedClipActivationPort publication,
            com.npick.pipeline.domain.model.StageRetrySettings retries) {
        // 실제 schema·정본 쓰기 어댑터는 #70이 연결한다. 미구현 성공 결과를 정상 저장으로 위장하지 않는다.
        StageOutputPort output = new StageOutputPort() {
            public Map<String, Object> validateAndStore(
                    long runId, long clipId, String stage, String prefix, Map<String, Object> result) {
                return requireOutput().validateAndStore(runId, clipId, stage, prefix, result);
            }

            private StageOutputPort requireOutput() {
                var adapter = outputs.getIfAvailable();
                if (adapter == null) throw new BusinessException(JobErrorCode.INTEGRATION_UNAVAILABLE);
                return adapter;
            }
        };
        return new StageExecutionService(runs, definitions, output, json, Clock.systemUTC(), publication, retries);
    }
}
