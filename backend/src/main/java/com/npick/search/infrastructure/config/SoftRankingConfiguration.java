package com.npick.search.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.npick.search.domain.model.SoftRankingSettings;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SoftRankingProperties.class)
public class SoftRankingConfiguration {

    /**
     * 신호가 전부 꺼져 있어도 빈을 만든다.
     *
     * <p>{@code SearchRankingConfiguration} 이 dense 채널에서 하듯 부팅을 막지 않는다 — 보조 신호가 없는 검색은 정상 상태이지 설정 오류가 아니다. 그리고 꺼진 설정도
     * {@code SearchConfigSnapshot} 에 남아야 켠 실행과 버전이 갈린다.
     */
    @Bean
    SoftRankingSettings softRankingSettings(SoftRankingProperties properties) {
        return properties.settings();
    }
}
