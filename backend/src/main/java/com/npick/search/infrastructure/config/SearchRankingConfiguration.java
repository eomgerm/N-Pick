package com.npick.search.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.npick.search.application.query.dense.DenseSearchSettings;
import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.LexicalSearchSettings;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({FusionProperties.class, DenseSearchProperties.class})
public class SearchRankingConfiguration {

    /**
     * dense 채널을 켜 두고 모델 버전을 비워 두면 부팅을 막는다.
     *
     * <p>이 조합을 허용하면 dense 가 매 검색마다 설정 오류로 실패하고, 사용자는 원인 없이 축소된 검색 결과를 받는다. 부팅 단계에서 드러내는 편이 낫다
     * ({@code SceneCandidateProperties.isAnyFieldSearched} 와 같은 이유다).
     */
    @Bean
    FusionSettings fusionSettings(FusionProperties fusion, DenseSearchProperties dense) {
        FusionSettings settings = fusion.settings();
        if (settings.isActive(FusionChannel.DENSE) && !dense.hasModelVersion()) {
            throw new IllegalStateException(
                    "npick.search.fusion.channel-weights.DENSE 가 0 보다 크면 " + "npick.search.dense.model-version 이 필요하다");
        }
        return settings;
    }

    /**
     * 단어 검색이 실제로 쓰는 설정을 순위 기록용으로 옮긴다.
     *
     * <p>{@code SceneCandidateProperties} 는 불변 record 싱글턴이라 후보 조회 어댑터가 주입받은 것과 같은 값이다. -53·-52 처럼 조회 결과가 설정을 실어 보내면 그쪽을
     * 쓰겠지만, -51 의 {@code SceneCandidateResult} 는 점수만 들고 온다.
     */
    @Bean
    LexicalSearchSettings lexicalSearchSettings(SceneCandidateProperties properties) {
        return new LexicalSearchSettings(
                properties.configVersion(),
                properties.captionWeight(),
                properties.transcriptWeight(),
                properties.ocrWeight(),
                properties.poolSize());
    }

    /** 모델 버전이 설정된 경우에만 만든다. 조립(-59)이 이 빈을 dense 조회에 넘긴다. */
    @Bean
    @ConditionalOnProperty(prefix = "npick.search.dense", name = "model-version")
    DenseSearchSettings denseSearchSettings(DenseSearchProperties properties) {
        return properties.settings();
    }
}
