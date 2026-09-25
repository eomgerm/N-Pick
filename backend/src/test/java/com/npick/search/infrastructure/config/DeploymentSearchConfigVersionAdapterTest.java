package com.npick.search.infrastructure.config;

import java.util.EnumMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.LexicalSearchSettings;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;

import static org.assertj.core.api.Assertions.assertThat;

/** 교정 상태 지문의 config 축이 보는 버전. 범용어 제외 목록(-320)을 바꾸면 검증 재검색의 drift guard 가 알아야 한다. */
class DeploymentSearchConfigVersionAdapterTest {

    @Test
    void changingTheExcludedQueryTokensChangesTheDeploymentConfigVersion() {
        String generic = adapter(List.of("장면/nng", "보이/vv", "화면/nng", "모습/nng")).currentConfigVersion();

        assertThat(adapter(List.of("장면/nng", "보이/vv", "화면/nng")).currentConfigVersion())
                .isNotEqualTo(generic);
        assertThat(adapter(List.of()).currentConfigVersion()).isNotEqualTo(generic);
        assertThat(adapter(List.of("장면/nng", "보이/vv", "화면/nng", "모습/nng")).currentConfigVersion())
                .isEqualTo(generic);
    }

    private static DeploymentSearchConfigVersionAdapter adapter(List<String> excludedQueryTokens) {
        var channels = new EnumMap<FusionChannel, Double>(FusionChannel.class);
        channels.put(FusionChannel.LEXICAL, 1.0);
        channels.put(FusionChannel.DENSE, 0.0);
        var structured = new EnumMap<StructuredAxis, Double>(StructuredAxis.class);
        for (StructuredAxis axis : StructuredAxis.values()) structured.put(axis, 0.0);
        var soft = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        for (SoftSignal signal : SoftSignal.values()) soft.put(signal, 0.0);
        return new DeploymentSearchConfigVersionAdapter(
                new FusionSettings(60, 0.0, channels, FusionSettings.WeightStatus.EXPERIMENTAL),
                new LexicalSearchSettings("candidate-v3", 1.0, 1.0, 1.0, 0.3, 200, excludedQueryTokens),
                new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, structured),
                new SoftRankingSettings(soft, 0, FusionSettings.WeightStatus.EXPERIMENTAL));
    }
}
