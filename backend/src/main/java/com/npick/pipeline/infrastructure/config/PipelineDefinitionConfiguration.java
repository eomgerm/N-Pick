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
    com.npick.pipeline.domain.model.StageRetrySettings stageRetrySettings(
            @Value("${npick.pipeline.profile:classpath:pipeline-profile.yml}") Resource profile)
            throws java.io.IOException {
        var properties =
                new YamlPropertySourceLoader().load("pipeline-retries", profile).getFirst();
        Map<String, Integer> attempts = new LinkedHashMap<>();
        for (String stage : PipelineStages.NAMES) {
            String override = "stage_overrides." + stage + ".retry_count";
            Object count = properties.containsProperty(override)
                    ? properties.getProperty(override)
                    : properties.getProperty("defaults.retry_count");
            String value = count == null ? "" : count.toString();
            if (!value.isEmpty() && !value.matches("[0-9]+"))
                throw new IllegalArgumentException("Invalid retry_count: " + stage);
            try {
                attempts.put(stage, value.isEmpty() ? 1 : Math.addExact(Integer.parseInt(value), 1));
            } catch (ArithmeticException | NumberFormatException failure) {
                throw new IllegalArgumentException("Invalid retry_count: " + stage, failure);
            }
        }
        var codes = new java.util.HashSet<String>();
        for (int index = 0; ; index++) {
            Object code = properties.getProperty("transient_errors[" + index + "]");
            if (code == null) break;
            codes.add(code.toString());
        }
        return new com.npick.pipeline.domain.model.StageRetrySettings(attempts, codes);
    }

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
