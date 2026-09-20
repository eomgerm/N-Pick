package com.npick.search.infrastructure.config;

import org.springframework.stereotype.Component;

import com.npick.common.persistence.SearchConfigVersionSupplier;
import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.LexicalSearchSettings;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.StructuredScoreSettings;

/**
 * 배포 시점 검색 설정 버전을 준다 (S15P21A501-83). 정적으로 주입되는 설정 빈(fusion·lexical·structured·soft)에서
 * {@link SearchConfigSnapshot#version()} 규약(공용 규약 「값 해시」)으로 만든다.
 *
 * <p>dense 는 {@code null} 로 둔다 — dense 설정은 조회 결과에 딸려 오는 런타임 값이라 검색 없이 읽을 수 없다. 지문의 config 축은
 * search_execution.config_version 과 별개의 「설정 구분 키」이며, -83·-84 가 같은 이 어댑터를 보므로 대칭은 유지된다.
 */
@Component
class DeploymentSearchConfigVersionAdapter implements SearchConfigVersionSupplier {

    private final FusionSettings fusion;
    private final LexicalSearchSettings lexical;
    private final StructuredScoreSettings structured;
    private final SoftRankingSettings soft;

    DeploymentSearchConfigVersionAdapter(
            FusionSettings fusion, LexicalSearchSettings lexical,
            StructuredScoreSettings structured, SoftRankingSettings soft) {
        this.fusion = fusion;
        this.lexical = lexical;
        this.structured = structured;
        this.soft = soft;
    }

    @Override
    public String currentConfigVersion() {
        return new SearchConfigSnapshot(fusion, lexical, null, structured, soft).version();
    }
}
