package com.npick.search.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.npick.search.application.query.dense.DenseSearchSettings;

/**
 * npick.search.dense — 텍스트 의미 검색 실행 설정 (S15P21A501-53 이 사용, -54 가 공급).
 *
 * <p>{@code model-version} 에 <b>기본값을 두지 않는다.</b> 이 값은 질의 임베딩이 실제로 쓰는 인코더와 같아야 하고 ({@code <모델 id>@<40자 커밋>}), 임의로 채우면
 * 저장된 벡터와 모델이 어긋난 채로 검색이 도는 것을 아무도 모르게 된다. 값이 없으면 dense 설정 빈을 만들지 않고, dense 채널이 켜져 있으면 부팅을 막는다.
 */
@ConfigurationProperties("npick.search.dense")
public record DenseSearchProperties(String modelVersion, Integer poolSize, Double maxDistance) {

    /** 실측으로 정한 운영값이 아니라 단어 검색 pool 과 맞춘 잠정값이다 ({@code npick.search.candidate.pool-size}). */
    private static final int DEFAULT_POOL_SIZE = 200;

    public boolean hasModelVersion() {
        return modelVersion != null && !modelVersion.isBlank();
    }

    /** 값을 안 주면 전 범위라 언제나 유효하다. 준 값만 코사인 거리 범위인지 본다 (NaN 은 어느 비교에도 걸리지 않으므로 부정으로 판정한다). */
    public boolean hasUsableMaxDistance() {
        return maxDistance == null || (maxDistance > 0 && maxDistance <= DenseSearchSettings.FULL_COSINE_RANGE);
    }

    public DenseSearchSettings settings() {
        return new DenseSearchSettings(
                modelVersion,
                poolSize == null ? DEFAULT_POOL_SIZE : poolSize,
                maxDistance == null ? DenseSearchSettings.FULL_COSINE_RANGE : maxDistance);
    }
}
