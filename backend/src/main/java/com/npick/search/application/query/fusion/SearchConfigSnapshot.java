package com.npick.search.application.query.fusion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.npick.search.application.query.dense.DenseSearchSettings;
import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.LexicalSearchSettings;
import com.npick.search.domain.model.SearchConfigVersion;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;
import com.npick.search.domain.model.StructuredScoreSettings;

/**
 * 이 검색 실행이 실제로 사용한 순위 설정 전체 — {@code search_execution.search_config_json} 에 그대로 쓰고, 같은 payload 에서
 * {@code config_version} 을 계산한다.
 *
 * <p><b>하위 설정을 문자열 라벨로 대신하지 않는다.</b> {@code candidate-v1} 만 담으면 {@code ocrWeight} 를 바꾸고 라벨 갱신을 빠뜨렸을 때 두 실행이 같은 버전으로
 * 보인다. 라벨은 사람이 읽도록 payload 안에 남기고, 버전은 값들에서 만든다 (F-05 완료 기준).
 *
 * <p><b>기록 시점에 환경 설정을 다시 읽지 않는다.</b> dense 와 구조화는 조회 결과에 딸려 온 설정을 그대로 받는다 ({@code DenseCandidatesResult.settings},
 * {@code StructuredScoresResult.settings}). 다시 읽으면 그 사이 바뀐 값이 기록돼 실행과 기록이 어긋난다.
 *
 * <p>설정 버전이 같다고 과거 순위가 그대로 재현되지는 않는다. 데이터·태그·적용 규칙이 달라지면 같은 설정으로도 결과가 달라진다 (FRD §7.2). 이 값은 <b>설정을 구분</b>하는 열쇠다.
 *
 * @param dense dense 채널이 꺼져 있으면 {@code null} 이다. 끈 실행과 켠 실행은 다른 설정이므로 버전도 달라야 한다
 * @param soft 보조 랭킹(-55) 설정. 신호가 전부 꺼져 있어도 {@code null} 이 아니다 — 끈 것과 켠 것은 다른 설정이고 그 차이가 버전에 남아야 한다
 */
public record SearchConfigSnapshot(
        FusionSettings fusion,
        LexicalSearchSettings lexical,
        DenseSearchSettings.Snapshot dense,
        StructuredScoreSettings structured,
        SoftRankingSettings soft) {

    /**
     * 이 스냅샷 payload 의 schema 이름 (docs/contracts/README.md 공용 규약 1).
     *
     * <p><b>{@code FusionSettings.SCHEMA} 를 빌려 쓰지 않는다.</b> 이 payload 는 fusion·lexical·dense·structured·soft 다섯을 담으므로 그
     * 중 하나의 이름을 쓰면 라벨이 가리키는 대상이 실제 내용과 다르다. 하위 설정의 schema 이름은 각자의 payload 안에 그대로 남는다.
     *
     * <p>값이 아니라 <b>구성</b>이 바뀌면 올린다 — 최상위 키가 늘거나 의미가 바뀔 때다.
     */
    public static final String SCHEMA = "search-config/v1";

    public SearchConfigSnapshot {
        Objects.requireNonNull(fusion, "fusion");
        Objects.requireNonNull(lexical, "lexical");
        Objects.requireNonNull(structured, "structured");
        Objects.requireNonNull(soft, "soft");
    }

    /** {@code search_execution.config_version}. 순위에 영향을 주는 값이 하나라도 바뀌면 달라진다. */
    public String version() {
        return SearchConfigVersion.of(SCHEMA, payload());
    }

    /**
     * {@code search_config_json} 에 저장할 값이자 버전 해시의 입력.
     *
     * <p>두 용도에 같은 payload 를 쓰는 것이 핵심이다. 저장하는 것과 해시하는 것이 갈리면 "기록된 설정" 과 "버전이 가리키는 설정" 이 달라진다.
     */
    public Map<String, Object> payload() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("fusion", fusionPayload());
        payload.put("lexical", lexicalPayload());
        payload.put("dense", densePayload());
        payload.put("structured", structuredPayload());
        payload.put("soft", softPayload());
        return payload;
    }

    private Map<String, Object> fusionPayload() {
        var channels = new TreeMap<String, Object>();
        for (FusionChannel channel : FusionChannel.values()) {
            channels.put(channel.name(), fusion.weightOf(channel));
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("schema", FusionSettings.SCHEMA);
        value.put("rrf_k", fusion.rrfK());
        value.put("lambda", fusion.lambda());
        value.put("weight_status", fusion.weightStatus().name());
        value.put("channel_weights", channels);
        return value;
    }

    private Map<String, Object> lexicalPayload() {
        var value = new LinkedHashMap<String, Object>();
        value.put("config_version", lexical.configVersion());
        value.put("caption_weight", lexical.captionWeight());
        value.put("transcript_weight", lexical.transcriptWeight());
        value.put("ocr_weight", lexical.ocrWeight());
        value.put("expanded_weight", lexical.expandedWeight());
        value.put("expanded_term_max_df", lexical.expandedTermMaxDf());
        value.put("pool_size", lexical.poolSize());
        return value;
    }

    private Object densePayload() {
        if (dense == null) return null;
        var value = new LinkedHashMap<String, Object>();
        value.put("schema", dense.schema());
        value.put("model_version", dense.modelVersion());
        value.put("pool_size", dense.poolSize());
        value.put("max_distance", dense.maxDistance());
        value.put("dimension", dense.dimension());
        value.put("metric", dense.metric());
        value.put("order", dense.order());
        value.put("algorithm", dense.algorithm());
        value.put("query_preprocessing", dense.queryPreprocessing());
        return value;
    }

    private Map<String, Object> softPayload() {
        var weights = new TreeMap<String, Object>();
        for (SoftSignal signal : SoftSignal.values()) {
            weights.put(signal.name(), soft.weightOf(signal));
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("schema", SoftRankingSettings.SCHEMA);
        value.put("tie_epsilon", soft.tieEpsilon());
        value.put("weight_status", soft.weightStatus().name());
        value.put("weights", weights);
        return value;
    }

    private Map<String, Object> structuredPayload() {
        var weights = new TreeMap<String, Object>();
        structured.weights().forEach((axis, weight) -> weights.put(axis.name(), weight));
        var value = new LinkedHashMap<String, Object>();
        value.put("weight_status", structured.weightStatus().name());
        value.put("weights", weights);
        return value;
    }
}
