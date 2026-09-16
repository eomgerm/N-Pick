package com.npick.search.infrastructure.config;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchRankingConfigurationTest {
    private final SearchRankingConfiguration configuration = new SearchRankingConfiguration();

    @Test
    void anActiveDenseChannelWithoutAModelVersionStopsTheApplicationFromStarting() {
        // 허용하면 dense 가 매 검색마다 설정 오류로 실패하고 사용자는 원인 없이 축소된 결과를 받는다.
        assertThatThrownBy(() -> configuration.fusionSettings(fusion(1.0, 1.0), new DenseSearchProperties(null, 200)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("npick.search.dense.model-version");
    }

    @Test
    void aBlankModelVersionIsTreatedAsMissingRatherThanPassedToTheDenseChannel() {
        assertThatThrownBy(() -> configuration.fusionSettings(fusion(1.0, 1.0), new DenseSearchProperties("  ", 200)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOffDenseChannelDoesNotRequireAModelVersion() {
        // 질의 임베딩(-164)이 dev 에 없는 현재 기본값이다. 임의 모델 버전을 지어내지 않는다.
        var settings = configuration.fusionSettings(fusion(1.0, 0.0), new DenseSearchProperties(null, null));
        assertThat(settings.isActive(FusionChannel.DENSE)).isFalse();
    }

    @Test
    void lexicalRecordingSettingsMirrorTheCandidateAdapterConfiguration() {
        // -51 의 결과는 설정을 실어 보내지 않으므로 어댑터가 주입받는 것과 같은 빈에서 읽는다.
        var properties = new SceneCandidateProperties("candidate-v1", 1.0, 2.0, 0.0, 200);
        var lexical = configuration.lexicalSearchSettings(properties);
        assertThat(lexical.configVersion()).isEqualTo("candidate-v1");
        assertThat(lexical.transcriptWeight()).isEqualTo(2.0);
        assertThat(lexical.ocrWeight()).isZero();
        assertThat(lexical.poolSize()).isEqualTo(200);
    }

    private static FusionProperties fusion(double lexical, double dense) {
        var weights = new EnumMap<FusionChannel, Double>(FusionChannel.class);
        weights.put(FusionChannel.LEXICAL, lexical);
        weights.put(FusionChannel.DENSE, dense);
        return new FusionProperties(FusionSettings.WeightStatus.EXPERIMENTAL, 60.0, 1.0, Map.copyOf(weights));
    }
}
