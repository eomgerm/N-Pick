package com.npick.pipeline.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.npick.pipeline.application.command.reclaim.ReclaimStagesUseCase;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ExpiredStageReaper {
    private final ReclaimStagesUseCase stages;

    public ExpiredStageReaper(ReclaimStagesUseCase stages) {
        this.stages = stages;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void reclaim() {
        stages.reclaim();
    }
}
