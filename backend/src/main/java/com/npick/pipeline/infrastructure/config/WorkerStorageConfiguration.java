package com.npick.pipeline.infrastructure.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import com.npick.pipeline.application.port.TagVocabularyPort;
import com.npick.pipeline.infrastructure.artifact.LocalWorkerArtifactAdapter;
import com.npick.pipeline.infrastructure.persistence.JdbcWorkerStageOutputAdapter;

/** Internal completion uses the same storage even when the HTTP job API is disabled. */
@Configuration
public class WorkerStorageConfiguration {
    @Bean
    JdbcWorkerStageOutputAdapter workerStageOutput(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            LocalWorkerArtifactAdapter artifacts,
            TagVocabularyPort vocabulary) {
        return new JdbcWorkerStageOutputAdapter(jdbc, mapper, artifacts, vocabulary);
    }

    @Bean
    LocalWorkerArtifactAdapter workerArtifacts(@Value("${npick.clip-registration.media-root}") Path root) {
        return new LocalWorkerArtifactAdapter(root);
    }
}
