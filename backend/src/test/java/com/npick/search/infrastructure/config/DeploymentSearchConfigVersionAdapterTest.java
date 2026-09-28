package com.npick.search.infrastructure.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.KeywordTagSettings;
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

    @Test
    void changingKeywordTagSettingsChangesTheDeploymentConfigVersion() {
        // 키워드 가중치·상한·제외 목록이 바뀌면 진행 중인 검증은 재검증 대상이어야 한다 (F-13.4 드리프트 가드).
        var base = new KeywordTagSettings(0.5, 12, List.of("앞", "북부"));
        String version = keywordAdapter(base).currentConfigVersion();

        assertThat(keywordAdapter(new KeywordTagSettings(0.4, 12, List.of("앞", "북부")))
                        .currentConfigVersion())
                .isNotEqualTo(version);
        assertThat(keywordAdapter(new KeywordTagSettings(0.5, 8, List.of("앞", "북부")))
                        .currentConfigVersion())
                .isNotEqualTo(version);
        assertThat(keywordAdapter(new KeywordTagSettings(0.5, 12, List.of("앞"))).currentConfigVersion())
                .isNotEqualTo(version);
        assertThat(keywordAdapter(KeywordTagSettings.OFF).currentConfigVersion())
                .isNotEqualTo(version);
        assertThat(keywordAdapter(new KeywordTagSettings(0.5, 12, List.of("앞", "북부")))
                        .currentConfigVersion())
                .isEqualTo(version);
    }

    @Test
    void snapshotPayloadRecordsKeywordTagSettings() {
        var snapshot = new SearchConfigSnapshot(
                fusion(), lexical(), null, structured(new KeywordTagSettings(0.5, 12, List.of("앞"))), soft());

        @SuppressWarnings("unchecked")
        var structured = (Map<String, Object>) snapshot.payload().get("structured");
        assertThat(structured.get("keyword"))
                .isEqualTo(Map.of("weight", 0.5, "condition_cap", 12, "stoplist", List.of("앞")));
    }

    @Test
    void changingCoverageWeightChangesTheDeploymentConfigVersion() {
        assertThat(coverageAdapter(0.2).currentConfigVersion())
                .isNotEqualTo(coverageAdapter(0.0).currentConfigVersion());
    }

    private static DeploymentSearchConfigVersionAdapter keywordAdapter(KeywordTagSettings keyword) {
        return new DeploymentSearchConfigVersionAdapter(fusion(), lexical(), structured(keyword), soft());
    }

    private static FusionSettings fusion() {
        var channels = new EnumMap<FusionChannel, Double>(FusionChannel.class);
        channels.put(FusionChannel.LEXICAL, 1.0);
        channels.put(FusionChannel.DENSE, 0.0);
        return new FusionSettings(60, 0.0, channels, FusionSettings.WeightStatus.EXPERIMENTAL);
    }

    private static LexicalSearchSettings lexical() {
        return new LexicalSearchSettings("candidate-v4", 1.0, 1.0, 1.0, 0.3, 0.0, 200, List.of());
    }

    private static StructuredScoreSettings structured(KeywordTagSettings keyword) {
        var weights = new EnumMap<StructuredAxis, Double>(StructuredAxis.class);
        for (StructuredAxis axis : StructuredAxis.values()) weights.put(axis, 0.0);
        return new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, weights, keyword);
    }

    private static SoftRankingSettings soft() {
        var weights = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        for (SoftSignal signal : SoftSignal.values()) weights.put(signal, 0.0);
        return new SoftRankingSettings(weights, 0, FusionSettings.WeightStatus.EXPERIMENTAL);
    }

    private static DeploymentSearchConfigVersionAdapter coverageAdapter(double coverageWeight) {
        return new DeploymentSearchConfigVersionAdapter(
                fusion(),
                new LexicalSearchSettings("candidate-v4", 1.0, 1.0, 1.0, 0.3, coverageWeight, 200, List.of()),
                structured(KeywordTagSettings.OFF),
                soft());
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
                new LexicalSearchSettings("candidate-v3", 1.0, 1.0, 1.0, 0.3, 0.0, 200, excludedQueryTokens),
                new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, structured),
                new SoftRankingSettings(soft, 0, FusionSettings.WeightStatus.EXPERIMENTAL));
    }
}
