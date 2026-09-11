package com.npick.pipeline.infrastructure.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import com.npick.pipeline.application.query.definition.GetPipelineDefinitionUseCase;
import com.npick.pipeline.domain.model.PipelineStages;
import com.npick.pipeline.infrastructure.json.JobJsonAdapter;

@Configuration(proxyBeanMethods = false)
public class PipelineDefinitionConfiguration {
    @Bean
    GetPipelineDefinitionUseCase pipelineDefinition(
            @Value("${npick.pipeline.profile:classpath:pipeline-profile.yml}") Resource profile)
            throws java.io.IOException {
        var properties =
                new YamlPropertySourceLoader().load("pipeline-profile", profile).getFirst();
        Map<String, String> versions = new LinkedHashMap<>();
        for (String stage : PipelineStages.NAMES) {
            Object configured = properties.getProperty("stage_versions." + stage);
            String value = configured == null ? "unknown" : configured.toString();
            if (!value.equals("unknown") && !value.matches("npick\\.stage\\." + stage + "/v[0-9]+:[0-9a-f]{8}"))
                throw new IllegalArgumentException("단계 기대 버전 형식: " + stage);
            versions.put(stage, value);
        }
        var definition = new GetPipelineDefinitionUseCase.Definition(
                "npick-pipeline/v1:" + new JobJsonAdapter().hash(versions).substring(0, 12),
                PipelineStages.NAMES,
                versions);
        return () -> definition;
    }
}
