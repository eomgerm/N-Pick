package com.npick.common.persistence;

/**
 * 검증 지문의 config 축이 쓰는 배포 시점 검색 설정 버전을 공급한다 (S15P21A501-83, FRD F-13.3 "검색 설정").
 *
 * <p>지문 계산은 {@code common} 에 있고 검색 설정 빈은 {@code search} 에 있으므로 seam 으로 분리한다. -83 기록과 -84 확정이
 * {@link CorrectionStateFingerprint} 를 통해 같은 값을 보므로 대칭이 깨지지 않는다. 설정이 바뀌면 값이 바뀌어 F-13.4 의
 * "검색 설정이 검증 때와 다르면 재검증" 이 성립한다.
 */
public interface SearchConfigVersionSupplier {
    String currentConfigVersion();
}
