package com.npick.search.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.npick.search.domain.model.StructuredScoreSettings;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StructuredScoreProperties.class)
public class StructuredScoreConfiguration {
    @Bean
    StructuredScoreSettings structuredScoreSettings(StructuredScoreProperties properties) {
        return properties.settings();
    }
}
